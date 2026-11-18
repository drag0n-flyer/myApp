import AppCore
import Foundation
import Observation

/// State of the chat screen that outlives a `ChatView`. `AppModel.swift` belongs to another task, so the
/// stored properties live here and `AppModel+Chat` reads them. Process lifetime only.
@MainActor
@Observable
final class ChatScreenState {
    static let shared = ChatScreenState()
    var reactionGate = ReactionGate()
    /// Set by chat info's Search button; the chat with this ID opens its search field and clears it.
    var searchRequestChatID: String?
    /// The chat whose screen is showing, set by `ChatView` while it is on screen (for the Mac's Search command).
    var visibleChatID: String?
    private var owner = OwnerGuard()
    private init() {}

    /// Drops everything when another account is signed in (a chat ID is the other person's account ID,
    /// so account B must not see account A's draft).
    func ensureOwner(_ id: String) {
        if owner.switchTo(id) { clearState() }
    }

    /// Reads never change state (they run while a view draws): another account's values read as empty.
    func isOwner(_ id: String) -> Bool { owner.isOwner(id) }

    /// For sign-out and account deletion.
    func reset() {
        owner.clear()
        clearState()
    }

    private func clearState() {
        reactionGate = ReactionGate()
        searchRequestChatID = nil
        visibleChatID = nil
    }
}

extension AppModel {
    /// The saved unsent text of a chat (kept in the store, so it survives a restart).
    /// Read through the store's accessor: a chat with no row yet still has its pending text.
    func draft(for chatID: String) -> String { chatStore.draft(for: chatID) }

    /// Does not touch `revision`: typing must not make the chat screen read its history again.
    func setDraft(_ text: String, for chatID: String) { chatStore.setDraft(chatID, text) }

    /// Saves drafts not yet written: when a chat is left and when the app stops being active.
    func flushDrafts() { chatStore.flushDrafts() }

    // MARK: Chat list actions (the stores call `onChange`, which refreshes every view)

    /// Chats this device archived. Every read touches `revision` so SwiftUI tracks store changes.
    var archivedChats: [Chat] { _ = revision; return chatStore.archivedChats }

    /// Every chat of the list, archived ones included (call history, folder picker, finding a chat of a contact).
    var everyChat: [Chat] { _ = revision; return chatStore.chats(in: .all) + chatStore.archivedChats }

    /// False when 5 chats are already pinned: the caller shows "You can pin up to 5 chats."
    @discardableResult
    func setPinned(_ chatID: String, _ pinned: Bool) -> Bool { chatStore.setPinned(chatID, pinned) }
    func setMuted(_ chatID: String, _ muted: Bool) { chatStore.setMuted(chatID, muted) }
    func setArchived(_ chatID: String, _ archived: Bool) { chatStore.setArchived(chatID, archived) }

    /// Chat whose search field should open (set by chat info's Search button).
    var chatSearchRequest: String? {
        get {
            let state = ChatScreenState.shared
            return state.isOwner(chatStore.me.id) ? state.searchRequestChatID : nil
        }
        set { ChatScreenState.shared.ensureOwner(chatStore.me.id); ChatScreenState.shared.searchRequestChatID = newValue }
    }

    private func isMine(_ message: Message) -> Bool { message.senderID == chatStore.me.id }

    /// The message as the store has it right now, or nil if it is gone. Messages older than the store's newest
    /// page are read back with `messages(before:)`, which needs the ID of the message that follows (`nextID`).
    func currentMessage(id: String, in chatID: String, followedBy nextID: String? = nil) -> Message? {
        if let found = chatStore.chat(id: chatID)?.messages.first(where: { $0.id == id }) { return found }
        guard let nextID else { return nil }
        // Ask for a few: a page can come back short when hidden lines are dropped after the database limit.
        return chatStore.messages(in: chatID, before: nextID, limit: 3).first { $0.id == id }
    }

    /// A page of messages before `messageID`, oldest first.
    func olderMessages(in chatID: String, before messageID: String, limit: Int = HistoryPager.pageSize) -> [Message] {
        chatStore.messages(in: chatID, before: messageID, limit: limit)
    }

    func send(_ content: MessageContent, in chatID: String, replyTo: String?, preview: LinkPreviewCard? = nil) {
        chatStore.send(content, in: chatID, replyTo: replyTo, preview: preview)
    }

    /// Reacts, unless the message vanished or was deleted, or this is a quick repeat. The store toggles.
    func react(_ emoji: String, to messageID: String, in chatID: String, followedBy nextID: String? = nil) {
        guard let current = currentMessage(id: messageID, in: chatID, followedBy: nextID),
              MessageActions.isStillApplicable(.react, current: current, isMine: isMine(current)),
              ChatScreenState.shared.reactionGate.allow(messageID: messageID, emoji: emoji, at: Date()) else { return }
        chatStore.react(emoji, to: messageID, in: chatID)
    }

    /// Edits an own text message. False when it can no longer be edited (deleted, failed, gone); the caller closes the edit bar.
    @discardableResult
    func edit(messageID: String, in chatID: String, text: String, followedBy nextID: String? = nil) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let current = currentMessage(id: messageID, in: chatID, followedBy: nextID),
              MessageActions.isStillApplicable(.edit, current: current, isMine: isMine(current)),
              !trimmed.isEmpty else { return false }
        chatStore.edit(messageID: messageID, in: chatID, text: trimmed)
        return true
    }

    /// Deletes an own message for everyone. False when it is gone, already deleted, or not ours.
    @discardableResult
    func delete(messageID: String, in chatID: String, followedBy nextID: String? = nil) -> Bool {
        guard let current = currentMessage(id: messageID, in: chatID, followedBy: nextID),
              MessageActions.isStillApplicable(.delete, current: current, isMine: isMine(current)) else { return false }
        chatStore.delete(messageID: messageID, in: chatID)
        return true
    }

    /// Sends `text` to the chat with `contactID` (created if needed) and returns that chat's ID.
    @discardableResult
    func forward(text: String, to contactID: String) -> String {
        let chatID = chatStore.ensureChat(with: contactID)
        chatStore.send(.text(text), in: chatID, replyTo: nil)
        return chatID
    }

    /// Message hits inside one chat, newest first.
    func searchMessages(_ query: String, in chatID: String) -> [MessageHit] {
        chatStore.searchMessages(query, in: chatID, limit: 500)
    }

    /// Opens the search bar of the chat on screen (the Mac's ⌘F calls this). Returns false, and does nothing,
    /// when no chat is open or the app lock covers the content.
    @discardableResult
    func openSearchInOpenChat() -> Bool {
        let state = ChatScreenState.shared
        guard !lock.coversContent, state.isOwner(chatStore.me.id), let id = state.visibleChatID else { return false }
        chatSearchRequest = id
        return true
    }

    /// `ChatView` reports that it is on screen, and again when it leaves.
    func chatAppeared(_ chatID: String) {
        ChatScreenState.shared.ensureOwner(chatStore.me.id)
        ChatScreenState.shared.visibleChatID = chatID
    }

    func chatDisappeared(_ chatID: String) {
        if ChatScreenState.shared.visibleChatID == chatID { ChatScreenState.shared.visibleChatID = nil }
    }
}
