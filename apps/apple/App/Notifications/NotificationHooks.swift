import AppCore
import Foundation

/// The three things Task A5 needs from tasks A4 and A6, which are built in parallel. Each is ONE function; the
/// merge replaces only its body.
@MainActor
enum NotificationHooks {
    /// Chats this device has muted. MERGE(A4): return `model.chatStore.mutedChatIDs`.
    static func mutedChatIDs() -> Set<String> { [] }

    /// True when app lock is on. MERGE(A6): return the app lock setting. Until then the lock reads false in the app.
    /// (The extension treats a missing or unreadable settings file as locked.)
    static func appLockOn() -> Bool { false }

    /// Appends `text` on a new line to the chat's draft. MERGE(A4): `chatStore.setDraft(chatID, ...)` with the draft
    /// read from the store. Until then the draft is the in-memory `DraftKeeper`, which does not survive the process.
    static func saveReplyDraft(chatID: String, text: String) {
        guard let model = NotificationActions.shared.model else { return }
        model.drafts.set(ReplyDraft.appending(text, to: model.drafts.draft(for: chatID)), for: chatID)
    }
}
