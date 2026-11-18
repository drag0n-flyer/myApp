import AppCore
import Foundation

/// What the notifications need from the chat list (mute, drafts) and from app lock.
@MainActor
enum NotificationHooks {
    /// Chats this device has muted.
    static func mutedChatIDs() -> Set<String> {
        NotificationActions.shared.model?.chatStore.mutedChatIDs ?? []
    }

    /// True when app lock is on. Unknown (no model yet) counts as on, like the extension's unreadable settings file.
    static func appLockOn() -> Bool {
        NotificationActions.shared.model?.appLockOn ?? true
    }

    /// Appends `text` on a new line to the chat's saved draft, and writes it at once (the app may be suspended next).
    static func saveReplyDraft(chatID: String, text: String) {
        guard let model = NotificationActions.shared.model else { return }
        model.chatStore.setDraft(chatID, ReplyDraft.appending(text, to: model.chatStore.draft(for: chatID)))
        model.chatStore.flushDrafts()
    }
}
