import AppCore
import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit

/// Why a group was not made, before anything was sent to the server.
public enum GroupCreateError: Error, Equatable, Sendable {
    /// The name fails `GroupNameRule` (1 to 50 code points, the display-name character rules).
    case invalidName
    /// A photo that is not a JPEG of 4 to 24,576 bytes.
    case invalidPhoto
    /// Fewer than 2 other people.
    case tooFewPeople
    /// More than 99 other people.
    case tooManyPeople
    /// The server's answer to the create is not a usable group (wrong kind, a bad ID, a list over 100, or without me).
    case badAnswer
}

/// What `createGroup` made.
public struct GroupCreation: Equatable, Sendable {
    public let chatID: String
    public let conversationID: String
    /// Accounts of the request that are not in the MLS group yet: no KeyPackage to claim, or left out by the server.
    /// The screen says "%@ couldn't be added yet" for each.
    public let notAdded: [String]
}

extension ConversationManager {
    /// `kv` key: the `group` (and own `profile`) envelopes of a new group are still to be queued.
    static func announceKey(_ serverID: String) -> String { "group-announce:\(serverID)" }

    /// Creates a group (spec 6.7, plan G2): validates, claims KeyPackages for every device of every person (and my other
    /// devices), asks the server for the group, then makes one MLS commit with all the welcomes and stores it as a
    /// pending create exactly like a one-to-one chat (`finishCreate` posts it, activates the chat and announces the
    /// mode). The `group {name, photo, ver: 1}` and own `profile` envelopes are queued, durably, only after that.
    ///
    /// A person without a KeyPackage is still a member on the server and is reported in `notAdded`; their devices are
    /// added later by a client that sees them missing. Nobody to add at all throws `SyncError.noKeyPackages` before
    /// the server is asked. A server error (`not_found`, `group_full`, `rate_limited`) is thrown as it is.
    public func createGroup(accounts requested: [String], name: String, photo: Data?) async throws -> GroupCreation {
        guard let cleanName = GroupNameRule.clean(name) else { throw GroupCreateError.invalidName }
        if let photo, !ProfilePhotoRule.isAcceptable(photo) {
            throw GroupCreateError.invalidPhoto
        }
        var seen: Set<String> = [deps.me.accountID, ""]
        let others = requested.filter { seen.insert($0).inserted }
        guard others.count >= 2 else { throw GroupCreateError.tooFewPeople }
        guard others.count <= GroupLimits.maxMembers - 1 else { throw GroupCreateError.tooManyPeople }

        // 1. KeyPackages for every device of every person, and for my other devices. A person the server will not
        //    hand packages for (none left, or an account that blocked me) is left for later, never an error.
        var claimed: [String: [ClaimedKeyPackage]] = [:]
        for account in others {
            do {
                let list = try await deps.server.claimKeyPackages(accountID: account)
                if !list.isEmpty { claimed[account] = list }
            } catch {
                if error.status == 404 { continue }
                throw error
            }
        }
        guard !claimed.isEmpty else { throw SyncError.noKeyPackages }
        // By device ID, as `DeviceSync` does, so this device's own package is not claimed and wasted.
        let ownOthers = try await deps.server.devices().map(\.id).filter { $0 != deps.me.deviceID }
        let mine = ownOthers.isEmpty ? [] : try await deps.server.claimKeyPackages(accountID: deps.me.accountID, deviceIDs: ownOthers)
            .filter { ownOthers.contains($0.deviceID) }

        // 2. The server group. Its member list is the authority for who is in.
        let created = try await deps.server.createGroup(accounts: others)
        let version = created.membersVersion ?? 0
        guard created.kind == "group", GroupLimits.isValidConversationID(created.conversationID),
              created.members.count <= GroupLimits.maxMembers, version >= 0,
              created.members.contains(where: { $0.account == deps.me.accountID }) else { throw GroupCreateError.badAnswer }
        let listed = Set(created.members.map(\.account))
        let added = others.filter { listed.contains($0) && claimed[$0] != nil }
        guard !added.isEmpty else { throw SyncError.noKeyPackages }
        let notAdded = others.filter { !added.contains($0) }
        let packages = added.flatMap { claimed[$0] ?? [] } + mine

        // 3. The MLS group and its one commit with every welcome; then, before anything is posted, the chat, the member
        //    list, the conversation row (inactive) and the commit, in one transaction.
        let repo = deps.repo, mls = deps.mls, now = deps.now()
        let chatID = ChatID.group(created.conversationID)
        let members = created.members.map { GroupMemberRecord(accountID: $0.account, role: $0.role.rawValue) }
        let record = try await locked { () async throws -> ConversationRecord in
            let group = try await mls.createGroup()
            let bundle = try await mls.add(keyPackages: packages.map(\.keyPackage), to: group)
            let record = ConversationRecord(
                serverID: created.conversationID, chatID: chatID, groupID: group.raw, mode: created.mode,
                innerMode: created.mode, modeMismatch: false, epoch: 0, active: false, createdAt: now)
            try repo.transaction {
                guard try repo.upsertGroup(conversationID: created.conversationID, title: cleanName, photo: photo, at: now) == chatID else {
                    throw GroupCreateError.badAnswer
                }
                _ = try repo.setGroupMembers(chatID: chatID, members, version: version)
                _ = try repo.setGroupInfo(chatID: chatID, title: cleanName, photo: photo, ver: 1)
                try repo.set(pendingKey(record.serverID), Data([1]))
                try repo.set(Self.announceKey(record.serverID), Data([1]))
                try repo.upsertConversation(record)
                try PendingCreate(commit: bundle.commit, welcome: bundle.welcome, addDevices: packages.map(\.deviceID))
                    .save(serverID: record.serverID, db: repo)
            }
            return record
        }

        // 4. Post, activate, announce the mode, queue the group envelopes.
        do {
            _ = try await finishCreate(serverID: record.serverID, resumed: false)
        } catch {
            // Still pending or already active: the group is durable and the next start finishes it (`resumePendingCreates`),
            // so the caller has a group, and a retry must not make a second one. Abandoned (the server refused the
            // commit for good, or the engine lost it): there is no group, so its chat goes and the error is passed on.
            let durable = try PendingCreate.isPending(serverID: record.serverID, db: repo) || activeOrNil(record.serverID) != nil
            if !durable {
                try? repo.deleteChat(id: chatID)
                throw error
            }
        }
        return GroupCreation(chatID: chatID, conversationID: created.conversationID, notAdded: notAdded)
    }

    /// Queues the `group` envelope (name, photo, version) and the own `profile` of a group this device created, once, in
    /// one transaction with clearing the marker, so a kill before it can only repeat the step and never lose it. It runs
    /// after the mode envelope, so it stays behind it. A no-op for any other conversation.
    func ensureGroupAnnounced(_ record: ConversationRecord) throws {
        guard ChatID.isGroup(record.chatID), let marker = try deps.repo.get(Self.announceKey(record.serverID)), !marker.isEmpty else {
            return
        }
        let repo = deps.repo, me = deps.me.accountID, now = deps.now()
        guard let chat = try repo.chat(id: record.chatID), let title = chat.title else {
            try repo.set(Self.announceKey(record.serverID), Data())
            return
        }
        var bodies: [ContentEnvelope.Body] = [.group(name: title, photo: chat.photo, ver: chat.groupVer ?? 1)]
        // The own profile travels with the group when this account has one stored (spec 6.4: sent when joined or created).
        if let own = try repo.profile(for: me) { bodies.append(.profile(name: own.name, photo: own.photo, ver: own.ver)) }
        let at = Int64((now.timeIntervalSince1970 * 1000).rounded())
        try repo.transaction {
            try repo.set(Self.announceKey(record.serverID), Data())
            for body in bodies {
                let envelope = ContentEnvelope(id: UUID().uuidString.lowercased(), at: at, body: body)
                try repo.enqueue(OutboxRecord(messageID: envelope.id, chatID: record.chatID, envelope: envelope.encoded(), attempts: 0, nextAttemptAt: now))
            }
        }
    }
}
