import AppCore
import Foundation
import LocalStore

/// What a group chat shows about its members: names (they need not be contacts) and the lists of Message info.
extension LiveChatStore {
    /// The contact's or profile name, else "Someone". The own name for this account. Cleaned like every other name.
    public func memberName(_ accountID: String) -> String {
        if accountID == deps.me.accountID { return me.displayName }
        let lookup = Self.name(of: contactRows[accountID]) ?? ""
        let name = ProfileDisplay.name(lookup: lookup, profile: profileRows[accountID], nickname: nil)
        return name.isEmpty ? Self.unknownMemberName : name
    }

    static let unknownMemberName = "Someone"

    /// "Read by" and "Delivered to" for one of my messages in a group chat: the current members other than me, with the
    /// time of their receipt. Empty for a one-to-one chat, another sender's message or a message of another chat.
    public func receiptLists(messageID: String, in chatID: String) -> MessageReceiptLists {
        let repo = deps.repo, me = deps.me.accountID
        guard ChatID.isGroup(chatID), let message = (try? repo.message(id: messageID)) ?? nil, message.chatID == chatID,
              message.senderAccount == me else { return MessageReceiptLists() }
        let members = ((try? repo.groupMembers(chatID: chatID)) ?? []).map(\.accountID)
        let receipts = ((try? repo.receipts(messageID: messageID)) ?? []).map { GroupReceipt(accountID: $0.accountID, kind: $0.kind, at: $0.at) }
        let lists = GroupTicks.lists(receipts: receipts, members: members, me: me)
        func entries(_ list: [GroupReceipt]) -> [ReceiptEntry] {
            list.map { ReceiptEntry(accountID: $0.accountID, name: memberName($0.accountID), at: $0.at) }
        }
        return MessageReceiptLists(readBy: entries(lists.readBy), deliveredTo: entries(lists.deliveredTo))
    }

    /// A one-to-one chat: anyone. A group: only an owner or admin of the stored list, and only while this device is in it.
    func mayChangeTimer(_ chatID: String) -> Bool {
        guard ChatID.isGroup(chatID) else { return true }
        guard let chat = (try? deps.repo.chat(id: chatID)) ?? nil, chat.kind == "group", chat.myState == "member" else { return false }
        let role = ((try? deps.repo.groupMembers(chatID: chatID)) ?? []).first { $0.accountID == deps.me.accountID }.map { GroupRole(wire: $0.role) }
        return role?.canSetTimer == true
    }
}
