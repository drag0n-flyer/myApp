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
    /// A Welcome labelled with a conversation ID that is already bound to another group, or naming a group that is
    /// already bound to another conversation ID.
    case conversationRebound
    /// My earlier add commit for a new conversation is still pending and its outcome is not known yet. It is kept
    /// (never discarded, never rebuilt); try again later.
    case commitInFlight
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
    func locked<T>(_ body: nonisolated(nonsending) () async throws -> T) async throws -> T {
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

    /// Claim, create, add; store the conversation (inactive, bound to its group) with the exact commit; post it;
    /// activate; then the `mode` envelope, before anything else can be sent.
    ///
    /// A pending create for `peer` (an earlier lost reply, timeout or kill) is finished instead: never a second
    /// group for the same peer. See `finishCreate`.
    public func create(with peer: String) async throws -> ConversationRecord {
        if let serverID = try pendingCreate(for: peer) {
            do {
                return try await finishCreate(serverID: serverID, resumed: true)
            } catch let error as ServerError {
                // The server refused that conversation for good: it was abandoned, so start a new one below.
                if try PendingCreate.isPending(serverID: serverID, db: deps.repo) { throw error }
            }
        }

        // 1. Claim KeyPackages for the peer and for my other devices.
        let theirs = try await deps.server.claimKeyPackages(accountID: peer)
        guard !theirs.isEmpty else { throw SyncError.noKeyPackages }
        let mine = try await deps.server.claimKeyPackages(accountID: deps.me.accountID)
            .filter { $0.deviceID != deps.me.deviceID }
        let claimed = theirs + mine

        // 2. The server conversation.
        let created = try await deps.server.createConversation(with: peer)

        // 3. The group and its add commit at epoch 0, then, before anything is posted, the conversation row
        //    (inactive) and the commit itself. Both modes come from the create response; the first one wins for
        //    `inner_mode`. I started this chat, so it is never a request.
        let repo = deps.repo, mls = deps.mls, now = deps.now()
        let record = try await locked { () async throws -> ConversationRecord in
            let group = try await mls.createGroup()
            let bundle = try await mls.add(keyPackages: claimed.map(\.keyPackage), to: group)
            let record = ConversationRecord(
                serverID: created.conversationID, chatID: peer, groupID: group.raw, mode: created.mode,
                innerMode: created.mode, modeMismatch: false, epoch: 0, active: false, createdAt: now)
            try repo.transaction {
                if try repo.chat(id: peer) == nil {
                    try repo.upsertChat(ChatRecord(id: peer, isRequest: false, unread: 0, muted: false, lastActivity: now))
                }
                try repo.set(pendingKey(record.serverID), Data([1]))
                try repo.upsertConversation(record)
                try PendingCreate(commit: bundle.commit, welcome: bundle.welcome, addDevices: claimed.map(\.deviceID))
                    .save(serverID: record.serverID, db: repo)
            }
            return record
        }

        // 4. Post, activate, announce.
        return try await finishCreate(serverID: record.serverID, resumed: false)
    }

    /// Finishes every pending create (run at start-up, after the first inbox sync). Failures stay pending; a
    /// conversation the server no longer has is abandoned, and no new one is started here.
    public func resumePendingCreates() async {
        guard let serverIDs = try? PendingCreate.all(db: deps.repo) else { return }
        for serverID in serverIDs {
            guard let peer = try? deps.repo.conversation(serverID: serverID)?.chatID else { continue }
            // Shares the per-peer slot with `preferredConversation`, so a send to this peer waits for it.
            if let running = creating[peer] { _ = try? await running.value; continue }
            let task = Task { try await self.finishCreate(serverID: serverID, resumed: true) }
            creating[peer] = task
            _ = try? await task.value
            creating[peer] = nil
        }
    }

    /// The oldest of `peer`'s conversations whose create is still pending.
    private func pendingCreate(for peer: String) throws -> String? {
        try deps.repo.conversations(chatID: peer).map(\.serverID).sorted()
            .first { try PendingCreate.isPending(serverID: $0, db: deps.repo) }
    }

    /// Settles the add commit of a pending create (MLS profile, "Commits" step 5, exception). At epoch 0 this
    /// device is the conversation's only member, so it is the only one that can move the server's epoch: an epoch
    /// past 0 means the server applied this very commit (the engine never builds another while it is pending), and
    /// an epoch of 0 means it did not, so posting the same commit again, byte for byte, cannot apply it twice.
    ///
    /// - `resumed`: read the inbox first (the echo activates the row), then ask the server's epoch.
    /// - `200`, or the server past epoch 0: merge and activate.
    /// - A `stale_epoch`: read the inbox and the server's epoch; past 0 means applied, else it stays pending.
    /// - A `404` (the conversation is gone): `commitRejected`; the row stays inactive and is never used. Any other
    ///   rejection keeps it pending.
    /// - An unknown outcome: read the inbox; still pending means the error is thrown and the create stays pending.
    private func finishCreate(serverID: String, resumed: Bool) async throws -> ConversationRecord {
        if resumed {
            await readInboxAfterUnknownOutcome()
            if let active = try activeOrNil(serverID) { return try await announced(active) }
            if try await serverEpoch(serverID) > 0 { return try await announced(try await acceptCreate(serverID)) }
        }
        guard let pending = try PendingCreate.load(serverID: serverID, db: deps.repo) else {
            guard let active = try activeOrNil(serverID) else { throw SyncError.commitInFlight }
            return try await announced(active)
        }
        do {
            _ = try await deps.server.postCommit(
                conversationID: serverID, epoch: 0, commit: pending.commit, welcome: pending.welcome,
                addDevices: pending.addDevices, removeDevices: [])
        } catch {
            if error.status == 409, error.code == "stale_epoch" {
                await readInboxAfterUnknownOutcome()
                if let active = try activeOrNil(serverID) { return try await announced(active) }
                if try await serverEpoch(serverID) > 0 { return try await announced(try await acceptCreate(serverID)) }
                throw error
            }
            if Self.isGone(error.status) {
                try await abandonCreate(serverID)
                throw error
            }
            await readInboxAfterUnknownOutcome()
            if let active = try activeOrNil(serverID) { return try await announced(active) }
            throw error
        }
        return try await announced(try await acceptCreate(serverID))
    }

    /// The server's epoch; a `404` (the conversation is gone) abandons the create.
    private func serverEpoch(_ serverID: String) async throws -> UInt64 {
        do {
            return try await deps.server.conversation(id: serverID).epoch
        } catch {
            if Self.isGone(error.status) { try await abandonCreate(serverID) }
            throw error
        }
    }

    /// The server refused the create for good: drop the pending commit; the row stays inactive and is never used.
    private func abandonCreate(_ serverID: String) async throws {
        let repo = deps.repo, mls = deps.mls
        let record = try repo.conversation(serverID: serverID)
        try await locked {
            if let record { try? await mls.commitRejected(GroupID(raw: record.groupID)) }
            try PendingCreate.clear(serverID: serverID, db: repo)
        }
    }

    private func activeOrNil(_ serverID: String) throws -> ConversationRecord? {
        guard let record = try deps.repo.conversation(serverID: serverID), record.active,
              try !PendingCreate.isPending(serverID: serverID, db: deps.repo) else { return nil }
        return record
    }

    /// The server applied the add commit: merge it (a no-op when the echo already did) and activate the row,
    /// with the members and the first safety number (no lines: it is new).
    private func acceptCreate(_ serverID: String) async throws -> ConversationRecord {
        let repo = deps.repo, mls = deps.mls, membership = membership
        return try await locked { () async throws -> ConversationRecord in
            guard var record = try repo.conversation(serverID: serverID) else { throw SyncError.commitInFlight }
            let group = GroupID(raw: record.groupID)
            try await mls.commitAccepted(group)
            let epoch = try await mls.epoch(of: group)
            // The engine must have merged the add commit; still at epoch 0 means it lost it, and the row stays
            // inactive (never usable at epoch 0).
            guard epoch > 0 else { throw SyncError.commitInFlight }
            let keys = try await membership.keys(of: group)
            return try repo.transaction {
                record = try repo.conversation(serverID: serverID) ?? record
                record.active = true
                record.epoch = Int64(epoch)
                try repo.upsertConversation(record)
                try PendingCreate.clear(serverID: serverID, db: repo)
                _ = try membership.apply(keys: keys, conversation: record, db: repo)
                return record
            }
        }
    }

    /// The `mode` envelope goes first, before anything else can be sent. If this post fails the pending marker
    /// stays and `preferredConversation` retries it before returning the conversation.
    private func announced(_ record: ConversationRecord) async throws -> ConversationRecord {
        try await ensureModeAnnounced(record)
        return record
    }

    /// `syncOnce` after an unknown outcome or a `stale_epoch`. A failure is ignored: the next attempt reads again.
    private func readInboxAfterUnknownOutcome() async {
        _ = try? await syncOnce()
    }

    /// A 4xx other than a timeout or a rate limit: the server will never accept this request.
    static func isDefiniteRejection(_ status: Int) -> Bool { (400..<500).contains(status) && status != 408 && status != 429 }

    /// The only answer that ends a pending create (MLS profile, step 5, exception): the conversation is gone. Any
    /// other rejection keeps it pending, and a later call tries again.
    static func isGone(_ status: Int) -> Bool { status == 404 }

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
        // A server ID already bound to another group is never rebound (it would move that chat's messages), and a
        // group already bound to another server ID is never bound twice (`group_id` is unique: the write would fail
        // on every retry and stall the inbox).
        if let existing = try deps.repo.conversation(serverID: serverConversationID), existing.groupID != group.raw {
            throw SyncError.conversationRebound
        }
        if let existing = try deps.repo.conversation(groupID: group.raw), existing.serverID != serverConversationID {
            throw SyncError.conversationRebound
        }
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
    func postCommitWithRetry(
        serverID: String, group: GroupID, addDevices: [String], removeDevices: [String],
        build: nonisolated(nonsending) () async throws -> CommitBundle
    ) async throws -> UInt64 {
        // The lock body must not touch `self`: a closure isolated to this actor that captures `build` (also isolated
        // to it) is treated as sending it, which Swift 6.2 rejects. `mls` is read here so the body is not isolated.
        let mls = deps.mls
        for attempt in 0...Self.maxStaleRetries {
            let (bundle, epoch) = try await locked {
                let bundle = try await build()
                return (bundle, try await mls.epoch(of: group))
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
                        try? await locked { try await deps.mls.commitRejected(group) }
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
