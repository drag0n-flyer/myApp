import AppCore
import Foundation

/// What the screens of a group chat read from the model: names of members (who need not be contacts), the typing line and
/// the "Name: text" preview. The decisions are the pure `GroupLines` and `GroupTyping` in AppCore.
extension AppModel {
    /// The name to show for a member of a group; "Someone" when nothing is known.
    func memberName(_ accountID: String) -> String {
        _ = revision
        return accountID == chatStore.me.id ? String(localized: "You") : chatStore.memberName(accountID)
    }

    /// "Read by" and "Delivered to" for one of my messages in a group chat.
    func receiptLists(messageID: String, in chatID: String) -> MessageReceiptLists {
        _ = revision
        return chatStore.receiptLists(messageID: messageID, in: chatID)
    }

    /// "%@ is typing…" or "%lld people are typing…" for a group chat; `nil` when nobody is typing. Nothing shows while the
    /// app-lock cover is up.
    func groupTypingText(chatID: String) -> String? {
        _ = revision
        guard ChatID.isGroup(chatID), !lock.coversContent, let people else { return nil }
        let names = people.typingAccounts(chatID: chatID).map { memberName($0) }
        switch GroupTyping.summary(names: names) {
        case .none: return nil
        case .one(let name): return String(localized: "\(name) is typing…")
        case .many(let count): return String(localized: "\(count) people are typing…")
        }
    }

    /// The name that goes in front of a group chat's last message in the list ("You", or a member's name); `nil` for
    /// no name. The preview of a blocked member's message says nothing about its content (see `PreviewText`).
    func previewSenderName(_ chat: Chat) -> String? {
        _ = revision
        switch GroupLines.previewSender(in: chat, me: chatStore.me.id) {
        case nil: return nil
        case .me?: return String(localized: "You")
        case .other(let id)?: return chatStore.memberName(id)
        }
    }
}
