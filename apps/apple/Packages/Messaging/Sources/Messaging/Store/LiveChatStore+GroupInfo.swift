import AppCore
import Foundation
import LocalStore

/// What group info reads: the stored name, photo, member list and the person's own place in it.
extension LiveChatStore {
    public func groupInfo(chatID: String) -> GroupInfo? {
        guard ChatID.isGroup(chatID), let chat = (try? deps.repo.chat(id: chatID)) ?? nil, chat.kind == "group",
              let records = try? deps.repo.groupMembers(chatID: chatID) else { return nil }
        let myID = deps.me.accountID
        let members = records.map { record -> GroupMemberRow in
            let isContact = groupMemberIsContact(record.accountID)
            return GroupMemberRow(accountID: record.accountID, name: groupMemberName(record.accountID, isContact: isContact),
                                  role: GroupRole(wire: record.role), isMe: record.accountID == myID, isContact: isContact,
                                  hasDirectChat: self.chat(id: record.accountID) != nil)
        }
        return GroupInfo(chatID: chatID, name: chat.title, photo: GroupScreenRules.displayablePhoto(chat.photo), members: members,
                         myRole: records.first { $0.accountID == myID }.map { GroupRole(wire: $0.role) },
                         myState: GroupMyState(wire: chat.myState))
    }

    /// A contact of this device (not just a person the app has seen).
    private func groupMemberIsContact(_ accountID: String) -> Bool {
        accountID != deps.me.accountID && contacts.contains { $0.id == accountID }
    }

    /// The contact's name; for someone who is not a contact, the name from their `profile` if one came, else "".
    private func groupMemberName(_ accountID: String, isContact: Bool) -> String {
        if accountID == deps.me.accountID { return me.displayName }
        if isContact, let name = contact(id: accountID)?.displayName { return name }
        guard let name = (try? deps.repo.profile(for: accountID))?.name, DisplayNameRule.clean(name) == name else { return "" }
        return name
    }
}
