import Foundation

/// In-memory chat store driven by `SampleData`.
@MainActor
public final class SampleChatStore: ChatStore {
    public var onChange: (@MainActor () -> Void)?
    private var verifiedChatIDs: Set<String> = []
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

    public func send(_ content: MessageContent, in chatID: String, replyTo: String?) {
        defer { onChange?() }
        append(Message(id: makeID(), senderID: me.id, sentAt: Date(), content: content, status: .sent, replyToID: replyTo, reactions: []), to: chatID)
    }

    public func sendAttachment(_ fileURL: URL, kind: MediaKind, in chatID: String) {
        let content: MessageContent = switch kind {
        case .photo, .video: .photo(bytes: 0)
        case .voice: .voice(duration: 0)
        case .file: .file(name: fileURL.lastPathComponent, bytes: 0)
        }
        send(content, in: chatID, replyTo: nil)
    }

    public func react(_ emoji: String, to messageID: String, in chatID: String) {
        updateMessage(messageID, in: chatID) { message in
            if let i = message.reactions.firstIndex(of: emoji) {
                message.reactions.remove(at: i)
            } else {
                message.reactions.append(emoji)
            }
        }
    }

    public func edit(messageID: String, in chatID: String, text: String) {
        updateMessage(messageID, in: chatID) { message in
            guard message.senderID == me.id, case .text = message.content else { return }
            message.content = .text(text)
            message.editedAt = Date()
        }
    }

    public func delete(messageID: String, in chatID: String) {
        updateMessage(messageID, in: chatID) { message in
            message.content = .deleted
            message.media = nil
            message.reactions = []
        }
    }

    /// Sample data has nothing to download.
    public func download(messageID: String, in chatID: String) {}

    public func recordCall(_ summary: CallSummary, contactID: String, outgoing: Bool, at date: Date) {
        defer { onChange?() }
        let chatID = ensureChat(with: contactID)
        let senderID = outgoing ? me.id : contactID
        append(Message(id: makeID(), senderID: senderID, sentAt: date, content: .call(summary), status: outgoing ? .sent : .delivered, replyToID: nil, reactions: []), to: chatID)
    }

    public func messages(in chatID: String, before messageID: String?, limit: Int) -> [Message] {
        guard let i = index(of: chatID), limit > 0 else { return [] }
        let all = allChats[i].messages
        var end = all.count
        if let messageID {
            guard let at = all.firstIndex(where: { $0.id == messageID }) else { return [] }
            end = at
        }
        return Array(all[max(0, end - limit)..<end])
    }

    public func searchMessages(_ query: String, limit: Int) -> [MessageHit] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty, limit > 0 else { return [] }
        var hits: [MessageHit] = []
        for chat in allChats where !chat.isRequest {
            for message in chat.messages.reversed() {
                guard case .text(let text) = message.content, text.localizedCaseInsensitiveContains(q) else { continue }
                hits.append(MessageHit(chatID: chat.id, messageID: message.id, preview: message.previewText))
                if hits.count == limit { return hits }
            }
        }
        return hits
    }

    public func safetyNumber(for chatID: String) -> SafetyNumber? {
        guard chat(id: chatID) != nil else { return nil }
        // Fixed fake digits derived from the chat ID; the real number comes from MLS keys.
        var seed = chatID.utf8.reduce(UInt32(7)) { ($0 &* 31) &+ UInt32($1) }
        let groups = (0..<12).map { _ -> String in
            seed = seed &* 1_664_525 &+ 1_013_904_223
            return String(format: "%05d", Int(seed >> 8) % 100_000)
        }
        return SafetyNumber(groups: groups, isVerified: verifiedChatIDs.contains(chatID))
    }

    public func setVerified(_ verified: Bool, chatID: String) {
        defer { onChange?() }
        if verified { verifiedChatIDs.insert(chatID) } else { verifiedChatIDs.remove(chatID) }
    }

    public func receive(_ content: MessageContent, from sender: Contact) {
        defer { onChange?() }
        let message = Message(id: makeID(), senderID: sender.id, sentAt: Date(), content: content, status: .delivered, replyToID: nil, reactions: [])
        let chatID = chatIDCreatingRequestIfNeeded(for: sender)
        append(message, to: chatID)
        if let i = index(of: chatID) { allChats[i].unreadCount += 1 }
    }

    public func accept(requestID: String) {
        defer { onChange?() }
        guard let i = index(of: requestID), allChats[i].isRequest else { return }
        allChats[i].isRequest = false
        if let sender = requestSenders.removeValue(forKey: allChats[i].contactID) {
            contacts.append(sender)
        }
    }

    public func block(contactID: String) {
        defer { onChange?() }
        blockedIDs.insert(contactID)
        allChats.removeAll { $0.contactID == contactID }
        contacts.removeAll { $0.id == contactID }
        requestSenders[contactID] = nil
    }

    public func report(chatID: String) {
        defer { onChange?() }
        reportedChatIDs.insert(chatID)
    }

    public func markRead(chatID: String) {
        defer { onChange?() }
        if let i = index(of: chatID) { allChats[i].unreadCount = 0 }
    }

    public func incomingCall(from caller: Contact, video: Bool) -> IncomingCallDecision {
        defer { onChange?() }
        if contacts.contains(where: { $0.id == caller.id }) { return .ring }
        let chatID = chatIDCreatingRequestIfNeeded(for: caller)
        let missed = Message(id: makeID(), senderID: caller.id, sentAt: Date(), content: .call(video ? .missedVideo : .missedAudio), status: .delivered, replyToID: nil, reactions: [])
        append(missed, to: chatID)
        return .silentMissed
    }

    public func addContact(username: String) async -> Contact? {
        defer { onChange?() }
        let name = username.lowercased()
        if let existing = contacts.first(where: { $0.username == name }) { return existing }
        guard let displayName = directory[name] else { return nil }
        let contact = Contact(id: name, displayName: displayName, username: name)
        contacts.append(contact)
        return contact
    }

    public func ensureChat(with contactID: String) -> String {
        defer { onChange?() }
        if let existing = allChats.first(where: { $0.contactID == contactID }) { return existing.id }
        guard let contact = contact(id: contactID) else { return "chat-\(contactID)" }
        return chatIDCreatingRequestIfNeeded(for: contact)
    }

    public func addFolder(name: String, chatIDs: Set<String>) -> ChatFolder {
        defer { onChange?() }
        let folder = ChatFolder(id: "folder-\(makeID())", name: name, kind: .custom)
        folders.append(folder)
        for i in allChats.indices where chatIDs.contains(allChats[i].id) {
            allChats[i].folderIDs.insert(folder.id)
        }
        return folder
    }

    public func renameFolder(id: String, to name: String) {
        defer { onChange?() }
        guard let i = folders.firstIndex(where: { $0.id == id && $0.kind == .custom }) else { return }
        folders[i].name = name
    }

    public func deleteFolder(id: String) {
        defer { onChange?() }
        guard let i = folders.firstIndex(where: { $0.id == id && $0.kind == .custom }) else { return }
        folders.remove(at: i)
        for c in allChats.indices { allChats[c].folderIDs.remove(id) }
    }

    public func moveFolder(id: String, toCustomIndex index: Int) {
        defer { onChange?() }
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

    private func updateMessage(_ messageID: String, in chatID: String, _ change: (inout Message) -> Void) {
        defer { onChange?() }
        guard let c = index(of: chatID), let m = allChats[c].messages.firstIndex(where: { $0.id == messageID }) else { return }
        change(&allChats[c].messages[m])
    }

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
