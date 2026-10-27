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
    private let lockTimeout: Duration
    private var creating: [String: Task<ConversationRecord, Error>] = [:]

    /// - Parameters:
    ///   - escrowKey: the server's escrow public key, needed only to seal the `mode` envelope's
    ///     escrow copy in a server-readable conversation (B1 replaces this with the server info document).
    ///   - syncOnce: `InboxSync.syncOnce()`, run after a rejected commit so the retry starts from the
    ///     current epoch. The default does nothing.
    public init(
        deps: MessagingDeps,
        escrowKey: @escaping @Sendable () async throws -> Data = { throw SyncError.escrowKeyUnavailable },
        syncOnce: @escaping @Sendable () async throws -> Void = {},
        lockTimeout: Duration = .seconds(5)
    ) {
        self.lockTimeout = lockTimeout
        self.deps = deps
        self.escrowKey = escrowKey
        self.syncOnce = syncOnce
    }

    /// Runs `body` under the process lock (Design 12). Used for every step that changes MLS state or group
    /// rows. Never held across a network call, and never nested (the lock is not re-entrant).
    func locked<T>(_ body: () async throws -> T) async throws -> T {
        try await deps.lock.withAsyncLock(timeout: lockTimeout, body)
    }

    private var membership: Membership { Membership(deps: deps) }

    // MARK: ConversationManaging

    public func preferredConversation(with peer: String) async throws -> ConversationRecord {
        if let existing = try deps.repo.preferredConversation(chatID: peer) {
            try await ensureModeAnnounced(existing)
            return existing
        }
        // Reentrancy: a second caller must wait for the first create, not start another.
        if let running = creating[peer] { return try await running.value }
        let task = Task { try await self.create(with: peer) }
        creating[peer] = task
        defer { creating[peer] = nil }
        return try await task.value
    }

    /// A single step: discard my pending commit, then bring the inbox up to date.
    public func handleStaleEpoch(_ conversation: ConversationRecord) async throws {
        try await locked { try await deps.mls.commitRejected(GroupID(raw: conversation.groupID)) }
        try await syncOnce()  // takes the lock itself
    }

    // MARK: Pending mode envelope

    // The creator's conversation is stored with `inner_mode` set, so "the mode envelope has not been
    // sent yet" is an explicit marker in the `kv` table: `mode-pending:<serverID>` (non-empty = pending).
    func pendingKey(_ serverID: String) -> String { "mode-pending:\(serverID)" }

    func isModePending(_ serverID: String) throws -> Bool {
        !(try deps.repo.get(pendingKey(serverID)) ?? Data()).isEmpty
    }

    /// Sends the creator's `mode` envelope if an earlier attempt failed. Every send path calls this
    /// (through `preferredConversation`) before anything else goes into the conversation.
    public func ensureModeAnnounced(_ conversation: ConversationRecord) async throws {
        guard try isModePending(conversation.serverID) else { return }
        try await announceMode(conversation)
    }

    // MARK: Create

    public func create(with peer: String) async throws -> ConversationRecord {
        // 1. Claim KeyPackages for the peer and for my other devices.
        let theirs = try await deps.server.claimKeyPackages(accountID: peer)
        guard !theirs.isEmpty else { throw SyncError.noKeyPackages }
        let mine = try await deps.server.claimKeyPackages(accountID: deps.me.accountID)
            .filter { $0.deviceID != deps.me.deviceID }
        let claimed = theirs + mine

        // 2. Server conversation, then the group.
        let created = try await deps.server.createConversation(with: peer)
        let group = try await locked { try await deps.mls.createGroup() }

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
        // The members and the first safety number are stored with the conversation (no lines: it is new).
        try await locked {
            let keys = try await membership.keys(of: group)
            try deps.repo.transaction {
                try deps.repo.set(pendingKey(record.serverID), Data([1]))
                try deps.repo.upsertConversation(record)
                _ = try membership.apply(keys: keys, conversation: record, db: deps.repo)
            }
        }

        // 5. The mode envelope goes first, before anything else can be sent. If this post fails the
        // pending marker stays and `preferredConversation` retries it before returning the conversation.
        try await announceMode(record)
        return record
    }

    // MARK: Join

    /// Called by the inbox processor after `mls.process` returned `.joined`.
    public func joined(group: GroupID, serverConversationID: String) async throws {
        let info = try await deps.server.conversation(id: serverConversationID)
        try await joined(group: group, serverConversationID: serverConversationID, serverMode: info.mode)
    }

    /// The local part of `joined`, with the server's mode already fetched. The inbox processor fetches it
    /// before it takes the process lock, so no network call happens under the lock.
    func joined(group: GroupID, serverConversationID: String, serverMode: String) async throws {
        let plan = try await planJoin(group: group, serverConversationID: serverConversationID, serverMode: serverMode)
        try deps.repo.transaction {
            try plan.store(in: deps.repo)
            _ = try membership.apply(keys: plan.keys, conversation: plan.conversation, db: deps.repo)
        }
    }

    /// The rows a Welcome creates. Built first (it awaits the MLS engine), written later by `store`, so the
    /// inbox processor can write them in the same transaction as `markProcessed`.
    struct JoinPlan: Sendable {
        let chat: ChatRecord
        let conversation: ConversationRecord
        /// The group's members, stored by `IncomingHandler.apply(memberKeys:)` or `joined`.
        let keys: [MemberKey]

        /// The chat is only created when it does not exist yet.
        func store(in repo: Repository) throws {
            if try repo.chat(id: chat.id) == nil { try repo.upsertChat(chat) }
            try repo.upsertConversation(conversation)
        }
    }

    func planJoin(group: GroupID, serverConversationID: String, serverMode: String) async throws -> JoinPlan {
        let keys = try await membership.keys(of: group)
        let identities = keys.map(\.identity)
        let accounts = Set(identities.map { String($0.split(separator: "/", maxSplits: 1)[0]) })
        guard let peer = accounts.subtracting([deps.me.accountID]).sorted().first else { throw SyncError.noPeer }
        let epoch = try await deps.mls.epoch(of: group)
        let isContact = try deps.repo.contact(id: peer)?.isContact ?? false
        // `inner_mode` stays NULL until the creator's `mode` envelope arrives.
        return JoinPlan(
            chat: ChatRecord(id: peer, isRequest: !isContact, unread: 0, muted: false, lastActivity: deps.now()),
            conversation: ConversationRecord(
                serverID: serverConversationID, chatID: peer, groupID: group.raw, mode: serverMode, innerMode: nil,
                modeMismatch: false, epoch: Int64(epoch), active: true, createdAt: deps.now()),
            keys: keys)
    }

    // MARK: Mode envelope

    /// Sends `.mode(inner_mode)` in the conversation (after any add commit).
    public func announceMode(_ conversation: ConversationRecord) async throws {
        guard let inner = conversation.innerMode else { throw SyncError.modeUnknown }
        let envelope = ContentEnvelope(
            id: UUID().uuidString.lowercased(), at: Int64((deps.now().timeIntervalSince1970 * 1000).rounded()), body: .mode(inner))
        let plaintext = envelope.encoded()
        // Encrypting advances the group's ratchet, so it is under the lock; the post is not.
        let (ciphertext, epoch) = try await locked { try await deps.mls.encrypt(plaintext, in: GroupID(raw: conversation.groupID)) }
        // Design 18: escrow only when both the creator's mode and the server's say server_readable.
        var escrow: Data?
        if inner == "server_readable", conversation.mode == "server_readable" {
            escrow = try await sealEscrow(envelope: plaintext, at: envelope.at, conversationID: conversation.serverID)
        }
        _ = try await deps.server.postMessage(
            conversationID: conversation.serverID, epoch: epoch, ciphertext: ciphertext, escrow: escrow,
            clientMsgID: envelope.id)
        try deps.repo.set(pendingKey(conversation.serverID), Data())
    }

    /// RFC 3339, UTC, milliseconds (ruling): `2026-10-28T12:00:00.123Z`.
    static func rfc3339(milliseconds: Int64) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter.string(from: Date(timeIntervalSince1970: Double(milliseconds) / 1000))
    }

    private func sealEscrow(envelope: Data, at: Int64, conversationID: String) async throws -> Data {
        let body = try JSONSerialization.jsonObject(with: envelope)
        let object: [String: Any] = [
            "conversationID": conversationID, "senderAccountID": deps.me.accountID,
            "sentAt": Self.rfc3339(milliseconds: at), "body": body,
        ]
        let plaintext = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])
        return try deps.escrow.seal(plaintext, to: try await escrowKey())
    }

    // MARK: Commit with epoch-conflict retry

    /// Builds a commit with `build`, posts it at the group's current epoch and merges it on success.
    /// On `stale_epoch`: `commitRejected`, `syncOnce`, rebuild and retry, at most 3 times, then
    /// `SyncError.epochConflict`. Returns the new epoch. A3 and A4 use this for their commits.
    ///
    /// Locking (Design 12): `build` and the epoch read run under the process lock; the post does not (a
    /// network call). The pending commit sits in the MLS storage during the post: the MLS commit protocol
    /// needs it there until the server answers, and the inbox processor never merges or discards it, only
    /// an echo of this device's own commit does. `commitAccepted` and `setEpoch` are one locked step.
    /// Drops my pending commit after a definite rejection. A busy lock (the other process is working) is
    /// retried; if it stays busy the failure is thrown instead of the rejection, so the caller knows the pending
    /// commit is still there and not silently left behind.
    private func discardPendingCommit(_ group: GroupID) async throws {
        var failure: Error?
        for _ in 0..<3 {
            do { try await locked { try await deps.mls.commitRejected(group) }; return } catch { failure = error }
        }
        if let failure { throw failure }
    }

    func postCommitWithRetry(
        serverID: String, group: GroupID, addDevices: [String], removeDevices: [String],
        build: () async throws -> CommitBundle
    ) async throws -> UInt64 {
        for attempt in 0...Self.maxStaleRetries {
            let (bundle, epoch) = try await locked { () async throws -> (CommitBundle, UInt64) in
                (try await build(), try await deps.mls.epoch(of: group))
            }
            let newEpoch: UInt64
            do {
                newEpoch = try await deps.server.postCommit(
                    conversationID: serverID, epoch: epoch, commit: bundle.commit, welcome: bundle.welcome,
                    addDevices: addDevices, removeDevices: removeDevices)
            } catch {
                guard error.status == 409, error.code == "stale_epoch" else {
                    // Only a definite rejection (4xx other than timeout/rate limit) discards the pending
                    // commit. Anything else (transport error, 5xx, lost reply) may have been applied by the
                    // server: keep it, so the echo of my own commit merges it (Design 17).
                    if (400..<500).contains(error.status), error.status != 408, error.status != 429 {
                        try await discardPendingCommit(group)
                    }
                    throw error
                }
                try await locked { try await deps.mls.commitRejected(group) }
                if attempt == Self.maxStaleRetries { throw SyncError.epochConflict }
                try await syncOnce()
                continue
            }
            // The echo skip (Design 17) needs the stored epoch to match the merged group. A conversation row that
            // does not exist yet (`create`) is stored by the caller with this epoch.
            try await locked {
                try await deps.mls.commitAccepted(group)
                try deps.repo.setEpoch(serverID: serverID, Int64(newEpoch))
                // Device lines and the safety number. Best effort: if the engine or database fails here, the next
                // update compares the whole stored state and writes the lines then. (`create` has no row yet and
                // stores the members itself.)
                if let conversation = try? deps.repo.conversation(serverID: serverID) {
                    _ = try? await membership.update(conversation: conversation, db: deps.repo)
                }
            }
            return newEpoch
        }
        throw SyncError.epochConflict
    }
}
