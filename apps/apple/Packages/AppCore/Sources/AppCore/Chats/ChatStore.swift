import Foundation

public enum IncomingCallDecision: Equatable, Sendable {
    /// Show the call screen.
    case ring
    /// Don't ring; record a missed call in the message request.
    case silentMissed
}

/// Chats, contacts, and message requests. `SampleChatStore` now; the real MLS-backed store later.
@MainActor
public protocol ChatStore: AnyObject {
    var me: Contact { get }
    var contacts: [Contact] { get }
    var folders: [ChatFolder] { get }
    func chats(in folder: ChatFolder) -> [Chat]
    func folderChipTitle(_ folder: ChatFolder) -> String
    var messageRequests: [Chat] { get }
    var messageRequestsRowTitle: String? { get }
    /// Account IDs this device has blocked (Settings > Privacy).
    var blockedIDs: Set<String> { get }
    func chat(id: String) -> Chat?
    func contact(id: String) -> Contact?
    /// Called after any change to the store's data.
    var onChange: (@MainActor () -> Void)? { get set }
    /// Up to `limit` messages before `messageID` (the newest if nil), oldest first.
    func messages(in chatID: String, before messageID: String?, limit: Int) -> [Message]
    func searchMessages(_ query: String, limit: Int) -> [MessageHit]
    func send(_ content: MessageContent, in chatID: String, replyTo: String?)
    func sendAttachment(_ fileURL: URL, kind: MediaKind, in chatID: String)
    func react(_ emoji: String, to messageID: String, in chatID: String)
    func edit(messageID: String, in chatID: String, text: String)
    func delete(messageID: String, in chatID: String)
    func download(messageID: String, in chatID: String)
    func recordCall(_ summary: CallSummary, contactID: String, outgoing: Bool, at date: Date)
    /// False while the engine cannot give real member keys: the Verify screen and the Verified seal stay hidden.
    var supportsSafetyNumbers: Bool { get }
    /// `nil` until the chat has a conversation.
    func safetyNumber(for chatID: String) -> SafetyNumber?
    /// Local only.
    func setVerified(_ verified: Bool, chatID: String)
    func receive(_ content: MessageContent, from contact: Contact)
    func accept(requestID: String)
    func block(contactID: String)
    func report(chatID: String)
    func markRead(chatID: String)
    func incomingCall(from contact: Contact, video: Bool) -> IncomingCallDecision
    func addContact(username: String) async -> Contact?
    /// The chat with this contact, created empty if there is none yet.
    func ensureChat(with contactID: String) -> String
    /// Custom folders only; All and Unread are fixed.
    func addFolder(name: String, chatIDs: Set<String>) -> ChatFolder
    func renameFolder(id: String, to name: String)
    func deleteFolder(id: String)
    func moveFolder(id: String, toCustomIndex index: Int)
}

extension ChatStore {
    public func send(_ content: MessageContent, in chatID: String) {
        send(content, in: chatID, replyTo: nil)
    }
}
