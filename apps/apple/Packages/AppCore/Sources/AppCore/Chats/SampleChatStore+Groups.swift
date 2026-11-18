import Foundation

/// Group info and changes in sample mode (`-sampleData`): in memory, with the rules the server enforces.
extension SampleChatStore {
    /// `-sampleMemberGroup`: a group where the person is a plain member (the owner is Riya, Arjun is an admin).
    public static let memberGroupChatID = "g:sample-members"

    func seedMemberGroup() {
        let id = Self.memberGroupChatID
        allChats.append(Chat(id: id, contactID: nil, encryption: .endToEnd, folderIDs: [], unreadCount: 0, messages: [],
                             isRequest: false, title: "Book club"))
        sampleGroups[id] = SampleGroup(members: [
            GroupMember(accountID: "riya", role: .owner), GroupMember(accountID: "arjun", role: .admin),
            GroupMember(accountID: me.id, role: .member), GroupMember(accountID: "sam", role: .member),
        ])
    }

    public func groupInfo(chatID: String) -> GroupInfo? {
        guard ChatID.isGroup(chatID), let chat = chat(id: chatID), let group = sampleGroups[chatID] else { return nil }
        let members = group.members.map { member in
            GroupMemberRow(accountID: member.accountID, name: contact(id: member.accountID)?.displayName ?? "", role: member.role,
                            isMe: member.accountID == me.id, isContact: contacts.contains { $0.id == member.accountID })
        }
        return GroupInfo(chatID: chatID, name: chat.title, photo: group.photo, members: members,
                         myRole: group.members.first { $0.accountID == me.id }?.role, myState: group.myState)
    }

    private func myRole(_ group: SampleGroup) -> GroupRole? { group.members.first { $0.accountID == me.id }?.role }

    public func addMembers(chatID: String, accounts: [String]) async -> GroupChangeOutcome {
        guard let group = sampleGroups[chatID] else { return .failed(.notFound) }
        guard GroupScreenRules.canAdd(myRole: myRole(group), myState: group.myState) else { return .failed(.forbidden) }
        let known = Set(group.members.map(\.accountID))
        let fresh = Array(Set(accounts).subtracting(known)).sorted()
        guard fresh.count <= GroupScreenRules.room(memberCount: group.members.count) else { return .failed(.full) }
        sampleGroups[chatID]?.members += fresh.map { GroupMember(accountID: $0, role: .member) }
        onChange?()
        return .done
    }

    public func removeMember(chatID: String, account: String) async -> GroupChangeOutcome {
        guard let group = sampleGroups[chatID] else { return .failed(.notFound) }
        guard group.myState == .member, account != me.id, let role = myRole(group),
              let target = group.members.first(where: { $0.accountID == account }) else { return .failed(.forbidden) }
        guard role.canRemove(target: target.role) else { return .failed(.forbidden) }
        sampleGroups[chatID]?.members.removeAll { $0.accountID == account }
        onChange?()
        return .done
    }

    public func leaveGroup(chatID: String) async -> GroupChangeOutcome {
        guard let group = sampleGroups[chatID] else { return .failed(.notFound) }
        guard group.myState == .member else { return .failed(.forbidden) }
        if myRole(group) == .owner, group.members.count > 1 { return .failed(.ownerMustTransfer) }
        sampleGroups[chatID]?.members.removeAll { $0.accountID == me.id }
        sampleGroups[chatID]?.myState = .left
        onChange?()
        return .done
    }

    public func setRole(chatID: String, account: String, role: GroupRole) async -> GroupChangeOutcome {
        guard let group = sampleGroups[chatID] else { return .failed(.notFound) }
        guard group.myState == .member, myRole(group)?.canSetRoles == true, account != me.id,
              let index = group.members.firstIndex(where: { $0.accountID == account }) else { return .failed(.forbidden) }
        guard group.members[index].role != .owner else { return .failed(.forbidden) }
        if role == .owner, let mine = group.members.firstIndex(where: { $0.accountID == me.id }) {
            sampleGroups[chatID]?.members[mine].role = .admin   // the old owner stays as an admin
        }
        sampleGroups[chatID]?.members[index].role = role
        onChange?()
        return .done
    }

    public func renameGroup(chatID: String, name: String) async -> GroupChangeOutcome {
        guard let group = sampleGroups[chatID] else { return .failed(.notFound) }
        guard GroupScreenRules.canEditInfo(myRole: myRole(group), myState: group.myState) else { return .failed(.forbidden) }
        guard let clean = GroupNameRule.clean(name), let i = allChats.firstIndex(where: { $0.id == chatID }) else { return .failed(.invalidName) }
        allChats[i].title = clean
        onChange?()
        return .done
    }

    public func setGroupPhoto(chatID: String, photo: Data?) async -> GroupChangeOutcome {
        guard let group = sampleGroups[chatID] else { return .failed(.notFound) }
        guard GroupScreenRules.canEditInfo(myRole: myRole(group), myState: group.myState) else { return .failed(.forbidden) }
        if let photo, GroupScreenRules.displayablePhoto(photo) == nil { return .failed(.invalidPhoto) }
        sampleGroups[chatID]?.photo = photo
        onChange?()
        return .done
    }

    public func deleteChat(chatID: String) {
        guard let group = sampleGroups[chatID], group.myState != .member else { return }
        sampleGroups[chatID] = nil
        allChats.removeAll { $0.id == chatID }
        onChange?()
    }
}
