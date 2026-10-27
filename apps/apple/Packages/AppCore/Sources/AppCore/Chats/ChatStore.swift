import Foundation
public enum IncomingCallDecision: Equatable, Sendable {
    /// Show the call screen.
    case ring
    /// Don't ring; record a missed call in the message request.
    case silentMissed
}

/// Chats, contacts, and message requests. `SampleChatStore` now; the real MLS-backed store later.
public protocol ChatStore: AnyObject {
    var me: Contact { get }
    var contacts: [Contact] { get }
    var folders: [ChatFolder] { get }
    func chats(in folder: ChatFolder) -> [Chat]
    func folderChipTitle(_ folder: ChatFolder) -> String
    var messageRequests: [Chat] { get }
    var messageRequestsRowTitle: String? { get }
    func chat(id: String) -> Chat?
    func contact(id: String) -> Contact?
    func send(_ content: MessageContent, in chatID: String)
    func receive(_ content: MessageContent, from contact: Contact)
    func accept(requestID: String)
    func block(contactID: String)
    func report(chatID: String)
    func markRead(chatID: String)
    func incomingCall(from contact: Contact, video: Bool) -> IncomingCallDecision
    func recordCall(_ summary: CallSummary, contactID: String, outgoing: Bool, at date: Date)
    func addContact(username: String) -> Contact?
    /// The chat with this contact, created empty if there is none yet.
    func ensureChat(with contactID: String) -> String
    /// Custom folders only; All and Unread are fixed.
    func addFolder(name: String, chatIDs: Set<String>) -> ChatFolder
    func renameFolder(id: String, to name: String)
    func deleteFolder(id: String)
    func moveFolder(id: String, toCustomIndex index: Int)
}
