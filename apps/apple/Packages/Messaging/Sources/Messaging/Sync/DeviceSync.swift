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
    private let lockTimeout: Duration

    /// - Parameter wipeMLS: deletes the MLS engine's storage. `MLSEngine` has no such call, so the app
    ///   passes one that removes the engine's database. Required: a revoked device must not keep key material.
    public init(
        deps: MessagingDeps, conversations: ConversationManager,
        wipeMLS: @escaping @Sendable () async throws -> Void, lockTimeout: Duration = .seconds(5)
    ) {
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

    /// Adds every own device that is not in a group yet, in every active conversation, then sends the
    /// `mode` envelope again so the new devices learn it (Design 18). Each conversation is tried even when
    /// another fails; the first error is thrown at the end.
    public func reconcileOwnDevices() async throws {
        let ids = try await deps.server.devices().map(\.id).filter { $0 != deps.me.deviceID }
        guard !ids.isEmpty else { return }
        var failure: Error?
        for conversation in try activeConversations() {
            do { try await addMissing(Set(ids), to: conversation) } catch { failure = failure ?? error }
        }
        if let failure { throw failure }
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

    /// Removes `"<me>/<deviceID>"` from every group that has it. If the revoked device is this one, the
    /// device wipes itself instead (it can no longer post commits).
    public func handleRevoked(deviceID: String) async throws {
        if deviceID == deps.me.deviceID {
            try await wipeLocalData()
            return
        }
        let identity = "\(deps.me.accountID)/\(deviceID)"
        var failure: Error?
        for conversation in try activeConversations() {
            do { try await remove(identity, deviceID: deviceID, from: conversation) } catch { failure = failure ?? error }
        }
        if let failure { throw failure }
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
    public func wipeLocalData() async throws {
        // No network inside: the whole wipe is one locked step, so the notification extension cannot
        // process an item into a half-deleted store.
        try await deps.lock.withAsyncLock(timeout: lockTimeout) {
            var failure: Error?
            do { try deps.repo.wipe() } catch { failure = error }
            do {
                if FileManager.default.fileExists(atPath: deps.mediaDirectory.path) {
                    try FileManager.default.removeItem(at: deps.mediaDirectory)
                }
            } catch { failure = failure ?? error }
            do { try await wipeMLS() } catch { failure = failure ?? error }
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
