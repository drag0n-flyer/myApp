import AppCore
import Foundation
import Observation

/// State of the chat screen that outlives a `ChatView`. `AppModel.swift` belongs to another task, so the
/// stored properties live here and `AppModel+Chat` reads them. Process lifetime only (spec 5.3 "Drafts").
@MainActor
@Observable
final class ChatScreenState {
    static let shared = ChatScreenState()
    var drafts = DraftKeeper()
    var reactionGate = ReactionGate()
    /// Set by chat info's Search button; the chat with this ID opens its search field and clears it.
    var searchRequestChatID: String?
    private var owner = OwnerGuard()
    private init() {}

    /// Drops everything when another account is signed in (a chat ID is the other person's account ID,
    /// so account B must not see account A's draft).
    func ensureOwner(_ id: String) {
        if owner.switchTo(id) { clearState() }
    }

    /// For sign-out and account deletion.
    func reset() {
        owner.clear()
        clearState()
    }

    private func clearState() {
        drafts = DraftKeeper()
        reactionGate = ReactionGate()
        searchRequestChatID = nil
    }
}

extension AppModel {
    /// Unsent composer text per chat for the life of the process.
    var drafts: DraftKeeper {
        get { ChatScreenState.shared.ensureOwner(chatStore.me.id); return ChatScreenState.shared.drafts }
        set { ChatScreenState.shared.ensureOwner(chatStore.me.id); ChatScreenState.shared.drafts = newValue }
    }

    /// Chat whose search field should open (set by chat info's Search button).
    var chatSearchRequest: String? {
        get { ChatScreenState.shared.ensureOwner(chatStore.me.id); return ChatScreenState.shared.searchRequestChatID }
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

    func send(_ content: MessageContent, in chatID: String, replyTo: String?) {
        chatStore.send(content, in: chatID, replyTo: replyTo)
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

    /// Message hits inside one chat.
    func searchMessages(_ query: String, in chatID: String) -> [MessageHit] {
        chatStore.searchMessages(query, in: chatID, limit: 100)
    }
}
