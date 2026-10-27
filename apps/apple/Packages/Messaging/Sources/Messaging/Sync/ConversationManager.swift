import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit

public enum SyncError: Error, Equatable, Sendable {
    /// The commit was rejected with `stale_epoch` on every attempt.
    case epochConflict
    /// `announceMode` on a conversation whose `inner_mode` is still `NULL`.
    case modeUnknown
    /// Neither the peer nor my other devices had a KeyPackage to claim.
    case noKeyPackages
    /// A Welcome for a group that has no member of another account.
    case noPeer
    /// The server's escrow key is not known, so an escrow copy cannot be sealed.
    case escrowKeyUnavailable
}

/// Creates and joins 1:1 conversations (Design 1, 18). Lane A's other tasks (inbox processor,
/// reconcile) call `joined`, `announceMode` and `postCommitWithRetry`.
public actor ConversationManager: ConversationManaging {
    /// One try plus this many retries after a `stale_epoch`.
    static let maxStaleRetries = 3

    private let deps: MessagingDeps
    private let escrowKey: @Sendable () async throws -> Data
    private let syncOnce: @Sendable () async throws -> Void
    private var creating: [String: Task<ConversationRecord, Error>] = [:]

    /// - Parameters:
    ///   - escrowKey: the server's escrow public key, needed only to seal the `mode` envelope's
    ///     escrow copy in a server-readable conversation (B1 replaces this with the server info document).
    ///   - syncOnce: `InboxSync.syncOnce()`, run after a rejected commit so the retry starts from the
    ///     current epoch. The default does nothing.
    public init(
        deps: MessagingDeps,
        escrowKey: @escaping @Sendable () async throws -> Data = { throw SyncError.escrowKeyUnavailable },
        syncOnce: @escaping @Sendable () async throws -> Void = {}
    ) {
        self.deps = deps
        self.escrowKey = escrowKey
        self.syncOnce = syncOnce
    }

    // MARK: ConversationManaging

    public func preferredConversation(with peer: String) async throws -> ConversationRecord {
        if let existing = try deps.repo.preferredConversation(chatID: peer) { return existing }
        // Reentrancy: a second caller must wait for the first create, not start another.
        if let running = creating[peer] { return try await running.value }
        let task = Task { try await self.create(with: peer) }
        creating[peer] = task
        defer { creating[peer] = nil }
        return try await task.value
    }

    /// A single step: discard my pending commit, then bring the inbox up to date.
    public func handleStaleEpoch(_ conversation: ConversationRecord) async throws {
        try await deps.mls.commitRejected(GroupID(raw: conversation.groupID))
        try await syncOnce()
    }

    // MARK: Create

    public func create(with peer: String) async throws -> ConversationRecord {
        // 1. Claim KeyPackages for the peer and for my other devices.
        let theirs = try await deps.server.claimKeyPackages(accountID: peer)
        let mine = try await deps.server.claimKeyPackages(accountID: deps.me.accountID)
            .filter { $0.deviceID != deps.me.deviceID }
        let claimed = theirs + mine
        guard !theirs.isEmpty else { throw SyncError.noKeyPackages }

        // 2. Server conversation, then the group.
        let created = try await deps.server.createConversation(with: peer)
        let group = try await deps.mls.createGroup()

        // 3. Commit at epoch 0 with the welcome.
        let epoch = try await postCommitWithRetry(
            serverID: created.conversationID, group: group, addDevices: claimed.map(\.deviceID), removeDevices: []
        ) { try await self.deps.mls.add(keyPackages: claimed.map(\.keyPackage), to: group) }

        // 4. Store. Both modes come from the create response; the first one wins for `inner_mode`.
        // I started this chat, so it is never a request.
        if try deps.repo.chat(id: peer) == nil {
            try deps.repo.upsertChat(ChatRecord(id: peer, isRequest: false, unread: 0, muted: false, lastActivity: deps.now()))
        }
        let record = ConversationRecord(
            serverID: created.conversationID, chatID: peer, groupID: group.raw, mode: created.mode, innerMode: created.mode,
            modeMismatch: false, epoch: Int64(epoch), active: true, createdAt: deps.now())
        try deps.repo.upsertConversation(record)

        // 5. The mode envelope goes first, before anything else can be sent.
        try await announceMode(record)
        return record
    }

    // MARK: Join

    /// Called by the inbox processor after `mls.process` returned `.joined`.
    public func joined(group: GroupID, serverConversationID: String) async throws {
        let info = try await deps.server.conversation(id: serverConversationID)
        let identities = try await deps.mls.members(of: group)
        let accounts = Set(identities.map { String($0.split(separator: "/", maxSplits: 1)[0]) })
        guard let peer = accounts.subtracting([deps.me.accountID]).sorted().first else { throw SyncError.noPeer }
        let epoch = try await deps.mls.epoch(of: group)

        if try deps.repo.chat(id: peer) == nil {
            let isContact = try deps.repo.contact(id: peer)?.isContact ?? false
            try deps.repo.upsertChat(ChatRecord(id: peer, isRequest: !isContact, unread: 0, muted: false, lastActivity: deps.now()))
        }
        // `inner_mode` stays NULL until the creator's `mode` envelope arrives.
        try deps.repo.upsertConversation(ConversationRecord(
            serverID: serverConversationID, chatID: peer, groupID: group.raw, mode: info.mode, innerMode: nil,
            modeMismatch: false, epoch: Int64(epoch), active: true, createdAt: deps.now()))
    }

    // MARK: Mode envelope

    /// Sends `.mode(inner_mode)` in the conversation (after any add commit).
    public func announceMode(_ conversation: ConversationRecord) async throws {
        guard let inner = conversation.innerMode else { throw SyncError.modeUnknown }
        let envelope = ContentEnvelope(
            id: UUID().uuidString.lowercased(), at: Int64(deps.now().timeIntervalSince1970 * 1000), body: .mode(inner))
        let plaintext = envelope.encoded()
        let (ciphertext, epoch) = try await deps.mls.encrypt(plaintext, in: GroupID(raw: conversation.groupID))
        // Design 18: escrow only when both the creator's mode and the server's say server_readable.
        var escrow: Data?
        if inner == "server_readable", conversation.mode == "server_readable" {
            escrow = try await sealEscrow(envelope: plaintext, conversationID: conversation.serverID)
        }
        _ = try await deps.server.postMessage(
            conversationID: conversation.serverID, epoch: epoch, ciphertext: ciphertext, escrow: escrow,
            clientMsgID: envelope.id)
    }

    private func sealEscrow(envelope: Data, conversationID: String) async throws -> Data {
        let body = try JSONSerialization.jsonObject(with: envelope)
        let object: [String: Any] = [
            "conversationID": conversationID, "senderAccountID": deps.me.accountID,
            "sentAt": Int64(deps.now().timeIntervalSince1970 * 1000), "body": body,
        ]
        let plaintext = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])
        return try deps.escrow.seal(plaintext, to: try await escrowKey())
    }

    // MARK: Commit with epoch-conflict retry

    /// Builds a commit with `build`, posts it at the group's current epoch and merges it on success.
    /// On `stale_epoch`: `commitRejected`, `syncOnce`, rebuild and retry, at most 3 times, then
    /// `SyncError.epochConflict`. Returns the new epoch. A3 and A4 use this for their commits.
    func postCommitWithRetry(
        serverID: String, group: GroupID, addDevices: [String], removeDevices: [String],
        build: () async throws -> CommitBundle
    ) async throws -> UInt64 {
        for attempt in 0...Self.maxStaleRetries {
            let bundle = try await build()
            let epoch = try await deps.mls.epoch(of: group)
            let newEpoch: UInt64
            do {
                newEpoch = try await deps.server.postCommit(
                    conversationID: serverID, epoch: epoch, commit: bundle.commit, welcome: bundle.welcome,
                    addDevices: addDevices, removeDevices: removeDevices)
            } catch {
                guard error.status == 409, error.code == "stale_epoch" else {
                    try? await deps.mls.commitRejected(group)
                    throw error
                }
                try await deps.mls.commitRejected(group)
                if attempt == Self.maxStaleRetries { throw SyncError.epochConflict }
                try await syncOnce()
                continue
            }
            try await deps.mls.commitAccepted(group)
            return newEpoch
        }
        throw SyncError.epochConflict
    }
}
