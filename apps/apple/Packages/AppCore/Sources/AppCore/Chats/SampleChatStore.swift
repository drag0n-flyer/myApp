import Foundation

/// In-memory chat store driven by `SampleData`.
public final class SampleChatStore: ChatStore {
    public let me: Contact
    public private(set) var contacts: [Contact]
    public private(set) var folders: [ChatFolder]
    public private(set) var blockedIDs: Set<String> = []
    public private(set) var reportedChatIDs: Set<String> = []
    private var allChats: [Chat]
    private let directory: [String: String]
    private var nextID = 0

    public init(data: SampleData = .default) {
        me = data.me
        contacts = data.contacts
        folders = data.folders
        allChats = data.chats
        directory = data.directory
        for sender in data.requestSenders { requestSenders[sender.id] = sender }
    }

    public func chats(in folder: ChatFolder) -> [Chat] {
        allChats
            .filter { !$0.isRequest }
            .filter { chat in
                switch folder.kind {
                case .all: true
                case .unread: chat.unreadCount > 0
                case .custom: chat.folderIDs.contains(folder.id)
                }
            }
            .sorted { ($0.lastMessage?.sentAt ?? .distantPast) > ($1.lastMessage?.sentAt ?? .distantPast) }
    }

    public func folderChipTitle(_ folder: ChatFolder) -> String {
        guard folder.kind == .unread else { return folder.name }
        let count = chats(in: .unread).count
        return count > 0 ? "\(folder.name) \(count)" : folder.name
    }

    public var messageRequests: [Chat] { allChats.filter(\.isRequest) }

    public var messageRequestsRowTitle: String? {
        let count = messageRequests.count
        return count > 0 ? "Message requests · \(count)" : nil
    }

    public func chat(id: String) -> Chat? { allChats.first { $0.id == id } }

    public func contact(id: String) -> Contact? {
        contacts.first { $0.id == id } ?? requestSenders[id]
    }

    public func send(_ content: MessageContent, in chatID: String) {
        append(Message(id: makeID(), senderID: me.id, sentAt: Date(), content: content, status: .sent, replyToID: nil, reactions: []), to: chatID)
    }

    public func receive(_ content: MessageContent, from sender: Contact) {
        let message = Message(id: makeID(), senderID: sender.id, sentAt: Date(), content: content, status: .delivered, replyToID: nil, reactions: [])
        let chatID = chatIDCreatingRequestIfNeeded(for: sender)
        append(message, to: chatID)
        if let i = index(of: chatID) { allChats[i].unreadCount += 1 }
    }

    public func accept(requestID: String) {
        guard let i = index(of: requestID), allChats[i].isRequest else { return }
        allChats[i].isRequest = false
        if let sender = requestSenders.removeValue(forKey: allChats[i].contactID) {
            contacts.append(sender)
        }
    }

    public func block(contactID: String) {
        blockedIDs.insert(contactID)
        allChats.removeAll { $0.contactID == contactID }
        contacts.removeAll { $0.id == contactID }
        requestSenders[contactID] = nil
    }

    public func report(chatID: String) { reportedChatIDs.insert(chatID) }

    public func markRead(chatID: String) {
        if let i = index(of: chatID) { allChats[i].unreadCount = 0 }
    }

    public func incomingCall(from caller: Contact, video: Bool) -> IncomingCallDecision {
        if contacts.contains(where: { $0.id == caller.id }) { return .ring }
        let chatID = chatIDCreatingRequestIfNeeded(for: caller)
        let missed = Message(id: makeID(), senderID: caller.id, sentAt: Date(), content: .call(video ? .missedVideo : .missedAudio), status: .delivered, replyToID: nil, reactions: [])
        append(missed, to: chatID)
        return .silentMissed
    }

    public func recordCall(_ summary: CallSummary, contactID: String, outgoing: Bool, at date: Date) {
        let chatID = ensureChat(with: contactID)
        let sender = outgoing ? me.id : contactID
        append(Message(id: makeID(), senderID: sender, sentAt: date, content: .call(summary), status: outgoing ? .sent : .delivered, replyToID: nil, reactions: []), to: chatID)
    }

    public func addContact(username: String) -> Contact? {
        let name = username.lowercased()
        if let existing = contacts.first(where: { $0.username == name }) { return existing }
        guard let displayName = directory[name] else { return nil }
        let contact = Contact(id: name, displayName: displayName, username: name)
        contacts.append(contact)
        return contact
    }

    public func ensureChat(with contactID: String) -> String {
        if let existing = allChats.first(where: { $0.contactID == contactID }) { return existing.id }
        guard let contact = contact(id: contactID) else { return "chat-\(contactID)" }
        return chatIDCreatingRequestIfNeeded(for: contact)
    }

    public func addFolder(name: String, chatIDs: Set<String>) -> ChatFolder {
        let folder = ChatFolder(id: "folder-\(makeID())", name: name, kind: .custom)
        folders.append(folder)
        for i in allChats.indices where chatIDs.contains(allChats[i].id) {
            allChats[i].folderIDs.insert(folder.id)
        }
        return folder
    }

    public func renameFolder(id: String, to name: String) {
        guard let i = folders.firstIndex(where: { $0.id == id && $0.kind == .custom }) else { return }
        folders[i].name = name
    }

    public func deleteFolder(id: String) {
        guard let i = folders.firstIndex(where: { $0.id == id && $0.kind == .custom }) else { return }
        folders.remove(at: i)
        for c in allChats.indices { allChats[c].folderIDs.remove(id) }
    }

    public func moveFolder(id: String, toCustomIndex index: Int) {
        let fixed = folders.filter { $0.kind != .custom }
        var custom = folders.filter { $0.kind == .custom }
        guard let from = custom.firstIndex(where: { $0.id == id }) else { return }
        let folder = custom.remove(at: from)
        custom.insert(folder, at: max(0, min(index, custom.count)))
        folders = fixed + custom
    }

    // MARK: - Private

    private var requestSenders: [String: Contact] = [:]

    private func index(of chatID: String) -> Int? { allChats.firstIndex { $0.id == chatID } }

    private func append(_ message: Message, to chatID: String) {
        if let i = index(of: chatID) { allChats[i].messages.append(message) }
    }

    private func chatIDCreatingRequestIfNeeded(for sender: Contact) -> String {
        if let existing = allChats.first(where: { $0.contactID == sender.id }) { return existing.id }
        let isContact = contacts.contains { $0.id == sender.id }
        if !isContact { requestSenders[sender.id] = sender }
        let chat = Chat(id: "chat-\(sender.id)", contactID: sender.id, encryption: .endToEnd, folderIDs: [], unreadCount: 0, messages: [], isRequest: !isContact)
        allChats.append(chat)
        return chat.id
    }

    private func makeID() -> String {
        nextID += 1
        return "local-\(nextID)"
    }
}
