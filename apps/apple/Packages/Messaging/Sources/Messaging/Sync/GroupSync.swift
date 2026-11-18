import AppCore
import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit

/// Why a change to a group was refused on this device before the server was asked.
public enum GroupAdminError: Error, Equatable, Sendable {
    /// Not a group chat, not a group I am still in, or no member list yet.
    case notGroup
    /// The person is not in my copy of the member list.
    case unknownMember
    /// My role (in this device's copy of the list) does not allow it. The server decides in the end.
    case forbidden
    case ownerMustTransfer
    case full
    case invalidName
    case invalidPhoto
}

/// The commit was not needed after all: another member did the work while this one waited for a new epoch.
private struct GroupAlreadyDone: Error {}

extension ConversationManager {
    // MARK: One reconcile at a time

    func acquireGroup() async {
        if !groupBusy {
            groupBusy = true
            return
        }
        await withCheckedContinuation { groupWaiters.append($0) }
    }

    func releaseGroup() {
        if groupWaiters.isEmpty {
            groupBusy = false
        } else {
            groupWaiters.removeFirst().resume()
        }
    }

    // MARK: Entry points

    /// A `members` item arrived: bring the group in line with the server. Never throws (the next start or item catches
    /// up); an unknown conversation is ignored. `actor` is the item's sender, used for the lines only when the list moved
    /// by exactly one version.
    public func groupMembersChanged(conversationID: String, actor: String?) async {
        try? await reconcileGroup(conversationID: conversationID, actor: actor)
    }

    /// Start-up and reconnect: every group this device is still in is fetched once and brought in line. A failure for one
    /// group does not stop the others.
    public func reconcileGroups() async {
        guard let chats = try? deps.repo.chats() else { return }
        for chat in chats where chat.kind == "group" && chat.myState == "member" {
            guard let id = ChatID.conversationID(chat.id) else { continue }
            try? await reconcileGroup(conversationID: id, actor: nil)
        }
    }

    // MARK: Reconcile

    /// Fetches the group's member list and applies it: the list and one set of lines (written once, in one transaction),
    /// then the MLS side (devices of people who are gone come out, a listed person with no device is added). A group
    /// with no chat or no conversation row on this device is ignored (an orphan, or one I was removed from). A `404`
    /// means I am no longer in it. A list that cannot be used (wrong kind or ID, over 100, a repeated account) changes nothing.
    /// - Returns: the listed accounts (not me) that still have no device in the MLS group.
    @discardableResult
    func reconcileGroup(conversationID: String, actor: String?) async throws -> [String] {
        guard GroupLimits.isValidConversationID(conversationID) else { return [] }
        await acquireGroup()
        defer { releaseGroup() }
        let repo = deps.repo, me = deps.me.accountID
        let chatID = ChatID.group(conversationID)
        guard let chat = try repo.chat(id: chatID), chat.kind == "group", chat.myState == "member",
              try repo.conversation(serverID: conversationID) != nil else { return [] }

        let info: NewConversation
        do {
            info = try await deps.server.conversation(id: conversationID)
        } catch {
            if error.status == 404 {
                try GroupLines.iAmOut(chatID: chatID, state: "removed", at: deps.now(), db: repo)
                return []
            }
            throw error
        }
        guard info.kind == "group", info.conversationID == conversationID else { return [] }
        if let refusal = Self.groupJoinRefusal(info, me: me) {
            // Not in the list: I was removed. Any other fault is a list this device does not trust: nothing changes.
            if refusal == "group_not_member" { try GroupLines.iAmOut(chatID: chatID, state: "removed", at: deps.now(), db: repo) }
            return []
        }
        guard let version = info.membersVersion else { return [] }

        let records = info.members.map { GroupMemberRecord(accountID: $0.account, role: $0.role.rawValue) }
        let now = deps.now()
        try repo.transaction {
            let stored = try repo.chat(id: chatID)?.membersVersion
            // A version that is not higher changes nothing (a stale answer, or the item of my own change).
            guard let old = try repo.setGroupMembers(chatID: chatID, records, version: version), !old.isEmpty else { return }
            // The sender of the item is the actor only when this is the very next version: a missed item means the lines say
            // what changed without naming who.
            let named = stored.map { version == $0 + 1 } ?? false
            try GroupLines.writeMemberLines(
                old: old, new: records, actor: named ? actor : nil, me: me, version: version, chatID: chatID,
                conversationID: conversationID, at: now, db: repo)
        }
        return try await syncMLS(conversationID: conversationID, listed: Set(records.map(\.accountID)))
    }

    /// The MLS side of a reconcile. Takes the devices of accounts that are not listed out of the group (a commit by any
    /// member; the first on the server wins, a loser rebuilds on the new epoch and finds nothing left to do), and adds the
    /// devices of a listed account that has none in the group yet. A person with no KeyPackage to claim stays listed and
    /// is returned: the next client that sees them missing adds them.
    private func syncMLS(conversationID: String, listed: Set<String>) async throws -> [String] {
        let repo = deps.repo, me = deps.me.accountID
        guard let conversation = try repo.conversation(serverID: conversationID), conversation.active else { return [] }
        let group = GroupID(raw: conversation.groupID)
        let mls = deps.mls
        @Sendable func identities() async throws -> [String] {
            try await Membership.members(of: group, conversationID: conversationID, mls: mls, db: repo)
        }
        let plan = GroupSyncPlan.plan(listed: listed, mlsIdentities: try await identities(), me: me)
        var failure: Error?
        if !plan.removeIdentities.isEmpty {
            do {
                _ = try await postCommitWithRetry(serverID: conversationID, group: group, addDevices: [], removeDevices: []) {
                    let gone = try await identities().filter { !listed.contains(GroupSyncPlan.account(of: $0)) }
                    if gone.isEmpty { throw GroupAlreadyDone() }
                    return try await mls.remove(members: gone, from: group)
                }
            } catch is GroupAlreadyDone {
            } catch { failure = error }
        }
        if !plan.addAccounts.isEmpty {
            do { try await addDevices(of: plan.addAccounts, to: conversation, group: group, listed: listed) } catch { failure = failure ?? error }
        }
        if let failure { throw failure }
        let present = Set(try await identities().map(GroupSyncPlan.account(of:)))
        return listed.filter { $0 != me && !present.contains($0) }.sorted()
    }

    private func addDevices(of accounts: [String], to conversation: ConversationRecord, group: GroupID, listed: Set<String>) async throws {
        let server = deps.server, mls = deps.mls, repo = deps.repo, serverID = conversation.serverID
        var claimed: [String: [ClaimedKeyPackage]] = [:]
        for account in accounts {
            do {
                let list = try await server.claimKeyPackages(accountID: account)
                if !list.isEmpty { claimed[account] = list }
            } catch {
                if error.status == 404 { continue }   // not claimable (gone, or blocked me): left for later
                throw error
            }
        }
        guard !claimed.isEmpty else { return }
        let packages = claimed.sorted { $0.key < $1.key }.flatMap(\.value)
        do {
            _ = try await postCommitWithRetry(
                serverID: serverID, group: group, addDevices: packages.map(\.deviceID), removeDevices: []
            ) {
                // After a stale epoch the inbox has been read: another member may have added them already.
                let present = Set(try await Membership.members(of: group, conversationID: serverID, mls: mls, db: repo)
                    .map(GroupSyncPlan.account(of:)))
                let use = claimed.filter { !present.contains($0.key) && listed.contains($0.key) }.sorted { $0.key < $1.key }.flatMap(\.value)
                if use.isEmpty { throw GroupAlreadyDone() }
                return try await mls.add(keyPackages: use.map(\.keyPackage), to: group)
            }
        } catch is GroupAlreadyDone {
            return
        }
        // The committer tells the new devices what a Welcome cannot: the mode (as for a new own device) and, from an owner or
        // admin, the group's name and photo with my own profile.
        guard let fresh = try repo.conversation(serverID: serverID) else { return }
        if fresh.innerMode != nil {
            try repo.set(pendingKey(serverID), Data([1]))
            try repo.set(sealedModeKey(serverID), Data())
            try await announceMode(fresh)
        }
        try queueGroupInfo(chatID: fresh.chatID)
    }

    /// Queues the current `group` envelope (same version: members who have it ignore it) and my own `profile`, when my role
    /// may send them and the group has a name.
    func queueGroupInfo(chatID: String) throws {
        let repo = deps.repo, me = deps.me.accountID, now = deps.now()
        guard let chat = try repo.chat(id: chatID), let title = chat.title, let ver = chat.groupVer,
              try repo.groupMembers(chatID: chatID).first(where: { $0.accountID == me }).map({ GroupRole(wire: $0.role).canEditInfo }) == true
        else { return }
        var bodies: [ContentEnvelope.Body] = [.group(name: title, photo: chat.photo, ver: ver)]
        if let own = try repo.profile(for: me) { bodies.append(.profile(name: own.name, photo: own.photo, ver: own.ver)) }
        let at = Int64((now.timeIntervalSince1970 * 1000).rounded())
        try repo.transaction {
            for body in bodies {
                let envelope = ContentEnvelope(id: UUID().uuidString.lowercased(), at: at, body: body)
                try repo.enqueue(OutboxRecord(messageID: envelope.id, chatID: chatID, envelope: envelope.encoded(), attempts: 0, nextAttemptAt: now))
            }
        }
    }

    // MARK: Changes (the server first, then MLS)

    /// The chat, its conversation ID and my role in it, for a group I am still in.
    private func groupContext(_ chatID: String) throws -> (chat: ChatRecord, conversationID: String, role: GroupRole, members: [GroupMemberRecord]) {
        let repo = deps.repo
        guard ChatID.isGroup(chatID), let conversationID = ChatID.conversationID(chatID), let chat = try repo.chat(id: chatID),
              chat.kind == "group", chat.myState == "member" else { throw GroupAdminError.notGroup }
        let members = try repo.groupMembers(chatID: chatID)
        guard let mine = members.first(where: { $0.accountID == deps.me.accountID }) else { throw GroupAdminError.notGroup }
        return (chat, conversationID, GroupRole(wire: mine.role), members)
    }

    /// Adds people: the server first (it holds the list and the limit), then a reconcile that writes the lines and commits
    /// the new devices with their Welcomes. Returns the requested people who are members but have no device in yet.
    public func addGroupMembers(chatID: String, accounts requested: [String]) async throws -> [String] {
        let context = try groupContext(chatID)
        guard context.role.canAdd else { throw GroupAdminError.forbidden }
        var seen: Set<String> = [deps.me.accountID, ""]
        let accounts = requested.filter { seen.insert($0).inserted }
        let fresh = accounts.filter { id in !context.members.contains { $0.accountID == id } }
        guard context.members.count + fresh.count <= GroupLimits.maxMembers else { throw GroupAdminError.full }
        guard !accounts.isEmpty else { return [] }
        _ = try await deps.server.addAccounts(conversationID: context.conversationID, accounts: accounts)
        let unreached = try await reconcileGroup(conversationID: context.conversationID, actor: deps.me.accountID)
        return unreached.filter { accounts.contains($0) }
    }

    /// Removes a person (or leaves, when it is me): the server first, then a reconcile whose MLS step takes their devices out.
    public func removeGroupMember(chatID: String, account: String) async throws {
        if account == deps.me.accountID { return try await leaveGroup(chatID: chatID) }
        let context = try groupContext(chatID)
        guard let target = context.members.first(where: { $0.accountID == account }) else { throw GroupAdminError.unknownMember }
        guard context.role.canRemove(target: GroupRole(wire: target.role)) else { throw GroupAdminError.forbidden }
        _ = try await deps.server.removeAccount(conversationID: context.conversationID, account: account)
        try await reconcileGroup(conversationID: context.conversationID, actor: deps.me.accountID)
    }

    /// Leaves. The server removes my devices from delivery at once, and the others take them out of the MLS group in their
    /// next commit. Here the chat becomes read-only. A group the server no longer knows counts as left.
    public func leaveGroup(chatID: String) async throws {
        let context = try groupContext(chatID)
        if context.role == .owner, context.members.count > 1 { throw GroupAdminError.ownerMustTransfer }
        do {
            _ = try await deps.server.removeAccount(conversationID: context.conversationID, account: deps.me.accountID)
        } catch {
            if error.status != 404 { throw error }
        }
        await acquireGroup()
        defer { releaseGroup() }
        try GroupLines.iAmOut(chatID: chatID, state: "left", at: deps.now(), db: deps.repo)
    }

    /// Sets another person's role (owner only); `owner` hands the group over.
    public func setGroupRole(chatID: String, account: String, role: GroupRole) async throws {
        let context = try groupContext(chatID)
        guard context.role.canSetRoles, account != deps.me.accountID else { throw GroupAdminError.forbidden }
        guard context.members.contains(where: { $0.accountID == account }) else { throw GroupAdminError.unknownMember }
        _ = try await deps.server.setRole(conversationID: context.conversationID, account: account, role: role)
        try await reconcileGroup(conversationID: context.conversationID, actor: deps.me.accountID)
    }

    /// A new name and/or photo (`setPhoto` says the photo is part of the change; `nil` then removes it): `group` with
    /// `ver + 1`, the chat updated and the lines written at once, the envelope queued in the same transaction. Nothing
    /// changes, and nothing is sent, when the result is the same as now.
    public func changeGroupInfo(chatID: String, name: String?, photo: Data?, setPhoto: Bool) async throws {
        let repo = deps.repo, me = deps.me.accountID, now = deps.now()
        let context = try groupContext(chatID)
        guard context.role.canEditInfo else { throw GroupAdminError.forbidden }
        var title = context.chat.title
        if let name {
            guard let clean = GroupNameRule.clean(name) else { throw GroupAdminError.invalidName }
            title = clean
        }
        guard let title else { throw GroupAdminError.invalidName }
        var newPhoto = context.chat.photo
        if setPhoto {
            if let photo, !ProfilePhotoRule.isAcceptable(photo) { throw GroupAdminError.invalidPhoto }
            newPhoto = photo
        }
        let nameChanged = title != context.chat.title, photoChanged = newPhoto != context.chat.photo
        guard nameChanged || photoChanged else { return }
        let ver = (context.chat.groupVer ?? 0) + 1
        let conversationID = context.conversationID
        let at = Int64((now.timeIntervalSince1970 * 1000).rounded())
        await acquireGroup()
        defer { releaseGroup() }
        try repo.transaction {
            guard try repo.setGroupInfo(chatID: chatID, title: title, photo: newPhoto, ver: ver) else { return }
            if nameChanged {
                try GroupLines.insert(.nameChanged(by: me, name: title), id: "sys-group-name-\(conversationID)-\(ver)", chatID: chatID,
                                      conversationID: conversationID, at: now, db: repo)
            }
            if photoChanged {
                try GroupLines.insert(.photoChanged(by: me), id: "sys-group-photo-\(conversationID)-\(ver)", chatID: chatID,
                                      conversationID: conversationID, at: now.addingTimeInterval(0.001), db: repo)
            }
            let envelope = ContentEnvelope(id: UUID().uuidString.lowercased(), at: at, body: .group(name: title, photo: newPhoto, ver: ver))
            try repo.enqueue(OutboxRecord(messageID: envelope.id, chatID: chatID, envelope: envelope.encoded(), attempts: 0, nextAttemptAt: now))
        }
    }
}
