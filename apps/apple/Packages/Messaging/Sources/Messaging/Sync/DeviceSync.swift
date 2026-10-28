import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit

/// What a new linked device gets once (Design 5): contacts, chats (no messages), folders, blocks and
/// settings. Plan 6 seals it into the link blob. Encoded as JSON.
public struct LinkSnapshot: Codable, Equatable, Sendable {
    public struct Contact: Codable, Equatable, Sendable {
        public var accountID: String, username: String, displayName: String
        public var isContact: Bool
        /// Milliseconds since 1970.
        public var addedAt: Int64
    }
    public struct Chat: Codable, Equatable, Sendable {
        public var id: String
        public var isRequest: Bool
        public var muted: Bool
    }
    public struct Folder: Codable, Equatable, Sendable {
        public var id: String, name: String
        public var position: Int
        public var chatIDs: [String]
    }
    public var v: Int
    public var contacts: [Contact]
    public var chats: [Chat]
    public var folders: [Folder]
    public var blocked: [String]
    /// The `kv` entries whose key starts with `DeviceSync.settingsPrefix`.
    public var settings: [String: Data]
}

public enum DeviceSyncError: Error, Equatable, Sendable {
    /// `importSnapshot` got bytes that are not a `LinkSnapshot` of version 1.
    case invalidSnapshot
    /// A wipe failed every try: this device stops all further work (no reconcile, revocation or KeyPackage
    /// call) until the app starts again and finishes the wipe.
    case halted
}

/// Own-device changes (Design 5, 18, 19): adds new own devices to every conversation, removes revoked ones,
/// keeps the KeyPackage pool filled, wipes the device, and moves the link snapshot.
public actor DeviceSync {
    /// Settings that travel in the snapshot live in `kv` under this prefix.
    public static let settingsPrefix = "setting."
    static let checkedAtKey = "kp.checkedAt"
    static let checkInterval: TimeInterval = 6 * 3600
    static let lowWater = 10
    static let refill = 50

    private let deps: MessagingDeps
    private let conversations: ConversationManager
    private let wipeMLS: @Sendable () async throws -> Void
    private let deleteSigningKey: @Sendable () async throws -> Void
    private let lockTimeout: Duration
    /// Set when `wipeLocalData` failed every try.
    private var halted = false

    /// - Parameter wipeMLS: deletes the MLS engine's storage. `MLSEngine` has no such call, so the app
    ///   passes one that removes the engine's database. Required: a revoked device must not keep key material.
    /// - Parameter deleteSigningKey: deletes this account's MLS signing key from the keychain (ruling: a wipe
    ///   removes it too). M5's `SigningKeyStore` is wired in here by integration I1; until then the default does
    ///   nothing. It runs on every wipe, after the other steps.
    public init(
        deps: MessagingDeps, conversations: ConversationManager,
        wipeMLS: @escaping @Sendable () async throws -> Void,
        deleteSigningKey: @escaping @Sendable () async throws -> Void = {}, lockTimeout: Duration = .seconds(5)
    ) {
        self.deleteSigningKey = deleteSigningKey
        self.lockTimeout = lockTimeout
        self.deps = deps
        self.conversations = conversations
        self.wipeMLS = wipeMLS
    }

    /// The group already has (or already lacks) the member by the time the commit is rebuilt after a
    /// stale epoch: another device did the work.
    private struct AlreadyDone: Error {}

    private func activeConversations() throws -> [ConversationRecord] {
        try deps.repo.chats().flatMap { try deps.repo.conversations(chatID: $0.id) }.filter(\.active)
    }

    // MARK: Own devices

    /// Brings every active conversation in line with `GET /v1/devices` (mls-profile "Removing a device"):
    /// - an own device that is listed and not in the group is added, then the `mode` envelope is sent again so
    ///   the new device learns it (Design 18);
    /// - an own device that is in the group and no longer listed is removed (a device that missed
    ///   `device.revoked` catches up here; the profile allows 24 hours from coming online), but only when the list
    ///   is non-empty and contains this device, and only when the device was missing from two lists at least ten
    ///   minutes apart (the first time is stored; `deps.now` is used) and was not listed within those ten minutes.
    ///   A bad list or a device linked during the run cannot remove anyone.
    /// Each conversation is tried even when another fails; the first error is thrown at the end.
    public func reconcileOwnDevices() async throws {
        guard !halted else { throw DeviceSyncError.halted }
        let records = try await deps.server.devices()
        let listed = Set(records.map(\.id))
        let others = listed.subtracting([deps.me.deviceID])
        // A wrong, empty or partial answer must never remove anything: the list has to contain this device.
        let trustworthy = listed.contains(deps.me.deviceID)
        let now = Self.milliseconds(deps.now())
        if trustworthy {
            for record in records {
                try deps.repo.set(Self.seenPrefix + record.id, Data(String(now).utf8))
                try deps.repo.set(Self.missingPrefix + record.id, Data())
            }
        }
        var stillStale: Set<String> = []
        var failure: Error?
        var removalFailed = false
        for conversation in try activeConversations() {
            // Separate steps: a failed add (say, no KeyPackages for a new device) must not stop removals.
            if !others.isEmpty {
                do { try await addMissing(others, to: conversation) } catch { failure = failure ?? error }
            }
            if trustworthy {
                do {
                    try await removeUnlisted(listed, from: conversation, now: now, stillStale: &stillStale)
                } catch {
                    removalFailed = true
                    failure = failure ?? error
                }
            }
        }
        // `stillStale` is complete only when every removal step ran (a failed add does not matter here).
        if trustworthy, !removalFailed {
            // A device that is no longer in any group needs no mark.
            for (key, value) in try deps.repo.entries(prefix: Self.missingPrefix)
            where !value.isEmpty && !stillStale.contains(String(key.dropFirst(Self.missingPrefix.count))) {
                try deps.repo.set(key, Data())
            }
        }
        if let failure { throw failure }
    }

    /// When an own device was first missing from a trusted list (milliseconds; empty when it was listed since).
    static let missingPrefix = "ownDevice.missingSince."
    /// When an own device was last listed (milliseconds).
    static let seenPrefix = "ownDevice.seenAt."
    /// How long a device must be missing, and unlisted, before reconcile removes it (decision log 2026-10-28).
    static let missingFor: Int64 = 10 * 60_000

    private static func milliseconds(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    private func stamp(_ key: String) throws -> Int64? {
        guard let data = try deps.repo.get(key), !data.isEmpty, let text = String(data: data, encoding: .utf8) else { return nil }
        return Int64(text)
    }

    private func removeUnlisted(
        _ listed: Set<String>, from conversation: ConversationRecord, now: Int64, stillStale: inout Set<String>
    ) async throws {
        let prefix = "\(deps.me.accountID)/"
        let stale = try await deps.mls.members(of: GroupID(raw: conversation.groupID))
            .filter { $0.hasPrefix(prefix) }.map { String($0.dropFirst(prefix.count)) }
            .filter { $0 != deps.me.deviceID && !listed.contains($0) }.sorted()
        for deviceID in stale {
            stillStale.insert(deviceID)
            // Only a device missing from two lists at least ten minutes apart, and not seen or linked in that time.
            guard let firstMissing = try stamp(Self.missingPrefix + deviceID) else {
                try deps.repo.set(Self.missingPrefix + deviceID, Data(String(now).utf8))
                continue
            }
            let lastSeen = try stamp(Self.seenPrefix + deviceID)
            if now - firstMissing < Self.missingFor || (lastSeen.map { now - $0 < Self.missingFor } ?? false) { continue }
            try await remove("\(deps.me.accountID)/\(deviceID)", deviceID: deviceID, from: conversation)
        }
    }

    private func addMissing(_ own: Set<String>, to conversation: ConversationRecord) async throws {
        let deps = self.deps
        let group = GroupID(raw: conversation.groupID)
        let serverID = conversation.serverID
        @Sendable func missing() async throws -> [String] {
            let members = Set(try await deps.mls.members(of: group))
            return own.filter { !members.contains("\(deps.me.accountID)/\($0)") }.sorted()
        }
        let todo = try await missing()
        if todo.isEmpty { return }

        // Only the missing devices (never this one, never one already in the group).
        let claimed = try await deps.server.claimKeyPackages(accountID: deps.me.accountID, deviceIDs: todo)
            .filter { todo.contains($0.deviceID) }
        guard !claimed.isEmpty else { throw SyncError.noKeyPackages }

        do {
            _ = try await conversations.postCommitWithRetry(
                serverID: serverID, group: group, addDevices: claimed.map(\.deviceID), removeDevices: []
            ) {
                // After a stale epoch the inbox has been read: another own device may have added them.
                let still = try await missing()
                let use = claimed.filter { still.contains($0.deviceID) }
                if use.isEmpty { throw AlreadyDone() }
                return try await deps.mls.add(keyPackages: use.map(\.keyPackage), to: group)
            }
        } catch is AlreadyDone {
            return
        }
        // `postCommitWithRetry` merged the commit and stored the epoch in one locked step.

        // The committer sends the mode again (Design 18). The marker makes a failed send retry on the next send.
        guard let fresh = try deps.repo.conversation(serverID: serverID), fresh.innerMode != nil else { return }
        try deps.repo.set(await conversations.pendingKey(serverID), Data([1]))
        try await conversations.announceMode(fresh)
    }

    /// A `device.revoked` event. The event is unsigned and the server never sends it to the revoked device itself,
    /// so it never removes or wipes by itself (owner ruling): for another device of this account it runs
    /// `reconcileOwnDevices`, whose two-lists-ten-minutes rule decides; one naming this device is ignored (a real
    /// revocation reaches this device as a 401 or close code 4003, and the app signs out and wipes then).
    /// The app goes through `ReconcileScheduler.deviceRevoked`, which also keeps it from overlapping a reconcile.
    public func handleRevoked(deviceID: String) async throws {
        guard !halted else { throw DeviceSyncError.halted }
        guard deviceID != deps.me.deviceID else { return }
        try await reconcileOwnDevices()
    }

    private func remove(_ identity: String, deviceID: String, from conversation: ConversationRecord) async throws {
        let deps = self.deps
        let group = GroupID(raw: conversation.groupID)
        guard try await deps.mls.members(of: group).contains(identity) else { return }
        do {
            _ = try await conversations.postCommitWithRetry(
                serverID: conversation.serverID, group: group, addDevices: [], removeDevices: [deviceID]
            ) {
                guard try await deps.mls.members(of: group).contains(identity) else { throw AlreadyDone() }
                return try await deps.mls.remove(members: [identity], from: group)
            }
        } catch is AlreadyDone {
            return
        }
    }

    // MARK: KeyPackages

    /// Checks the server's KeyPackage count at most every 6 hours and uploads 50 when it is below 10.
    public func replenishKeyPackages() async throws {
        guard !halted else { throw DeviceSyncError.halted }
        let now = deps.now()
        if let data = try deps.repo.get(Self.checkedAtKey), let text = String(data: data, encoding: .utf8),
           let ms = Int64(text), now.timeIntervalSince(Date(timeIntervalSince1970: Double(ms) / 1000)) < Self.checkInterval {
            return
        }
        if try await deps.server.keyPackageCount() < Self.lowWater {
            // Generating stores private keys in the MLS storage (lock); the upload is a network call (no lock).
            let packages = try await deps.lock.withAsyncLock(timeout: lockTimeout) {
                try await deps.mls.makeKeyPackages(count: Self.refill)
            }
            _ = try await deps.server.uploadKeyPackages(packages)
        }
        // Only a completed check counts; a failure above is retried on the next call.
        let ms = Int64((now.timeIntervalSince1970 * 1000).rounded())
        try deps.repo.set(Self.checkedAtKey, Data(String(ms).utf8))
    }

    // MARK: Wipe

    /// Deletes the database rows, the media directory and the MLS storage. Every step runs; the first
    /// error is thrown at the end.
    /// A failed wipe is tried again (`wipeAttempts` in all); if it still fails, this device stops all further
    /// work (`DeviceSyncError.halted`) and the error is thrown.
    public func wipeLocalData() async throws {
        do {
            try await Self.wipe(
                repo: deps.repo, mediaDirectory: deps.mediaDirectory, lock: deps.lock, wipeMLS: wipeMLS,
                deleteSigningKey: deleteSigningKey, lockTimeout: lockTimeout)
        } catch {
            halted = true
            throw error
        }
    }

    /// How many times a wipe is tried before it gives up, and the pause between tries.
    public static let wipeAttempts = 3
    static let wipeRetryDelay: Duration = .milliseconds(200)

    /// The wipe without a running graph (the app calls it at launch to finish a wipe that was cut short). Rows,
    /// media, MLS storage, then the signing key.
    public static func wipe(
        repo: Repository, mediaDirectory: URL, lock: ProcessLock,
        wipeMLS: @Sendable () async throws -> Void, deleteSigningKey: @Sendable () async throws -> Void = {},
        lockTimeout: Duration = .seconds(5)
    ) async throws {
        // A wiped device's data cannot come back, and leftover data must not stay: a failed wipe is tried again,
        // `wipeAttempts` times in all. Every try runs every step; the last try's first error is thrown.
        var attempt = 1
        while true {
            do {
                try await wipeOnce(
                    repo: repo, mediaDirectory: mediaDirectory, lock: lock, wipeMLS: wipeMLS,
                    deleteSigningKey: deleteSigningKey, lockTimeout: lockTimeout)
                return
            } catch {
                guard attempt < wipeAttempts else { throw error }
                attempt += 1
                try? await Task.sleep(for: wipeRetryDelay)
            }
        }
    }

    private static func wipeOnce(
        repo: Repository, mediaDirectory: URL, lock: ProcessLock,
        wipeMLS: @Sendable () async throws -> Void, deleteSigningKey: @Sendable () async throws -> Void,
        lockTimeout: Duration
    ) async throws {
        // No network inside: the whole wipe is one locked step, so the notification extension cannot
        // process an item into a half-deleted store.
        try await lock.withAsyncLock(timeout: lockTimeout) {
            var failure: Error?
            do { try repo.wipe() } catch { failure = error }
            do {
                if FileManager.default.fileExists(atPath: mediaDirectory.path) {
                    try FileManager.default.removeItem(at: mediaDirectory)
                }
            } catch { failure = failure ?? error }
            do { try await wipeMLS() } catch { failure = failure ?? error }
            do { try await deleteSigningKey() } catch { failure = failure ?? error }
            if let failure { throw failure }
        }
    }

    // MARK: Link snapshot

    public func exportSnapshot() throws -> Data {
        let repo = deps.repo
        let contacts = try repo.contacts().map {
            LinkSnapshot.Contact(
                accountID: $0.accountID, username: $0.username, displayName: $0.displayName, isContact: $0.isContact,
                addedAt: Int64(($0.addedAt.timeIntervalSince1970 * 1000).rounded()))
        }
        let chats = try repo.chats().map { LinkSnapshot.Chat(id: $0.id, isRequest: $0.isRequest, muted: $0.muted) }
        let folders = try repo.folders().map {
            LinkSnapshot.Folder(id: $0.id, name: $0.name, position: $0.position, chatIDs: try repo.folderChats(folderID: $0.id))
        }
        let snapshot = LinkSnapshot(
            v: 1, contacts: contacts, chats: chats, folders: folders, blocked: try repo.blockedIDs(),
            settings: try repo.entries(prefix: Self.settingsPrefix))
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(snapshot)
    }

    /// Applies a snapshot in one transaction. Existing rows are kept as they are (chats and folders by ID).
    public func importSnapshot(_ data: Data) throws {
        guard let snapshot = try? JSONDecoder().decode(LinkSnapshot.self, from: data), snapshot.v == 1 else {
            throw DeviceSyncError.invalidSnapshot
        }
        let repo = deps.repo
        let now = deps.now()
        try repo.transaction {
            for c in snapshot.contacts {
                try repo.upsertContact(ContactRecord(
                    accountID: c.accountID, username: c.username, displayName: c.displayName, isContact: c.isContact,
                    addedAt: Date(timeIntervalSince1970: Double(c.addedAt) / 1000)))
            }
            for c in snapshot.chats where try repo.chat(id: c.id) == nil {
                try repo.upsertChat(ChatRecord(id: c.id, isRequest: c.isRequest, unread: 0, muted: c.muted, lastActivity: now))
            }
            let existing = Set(try repo.folders().map(\.id))
            for f in snapshot.folders where !existing.contains(f.id) {
                try repo.addFolder(FolderRecord(id: f.id, name: f.name, position: f.position))
                try repo.setFolderChats(folderID: f.id, chatIDs: f.chatIDs)
            }
            for id in snapshot.blocked { try repo.block(id) }
            for (key, value) in snapshot.settings where key.hasPrefix(Self.settingsPrefix) { try repo.set(key, value) }
        }
    }
}
