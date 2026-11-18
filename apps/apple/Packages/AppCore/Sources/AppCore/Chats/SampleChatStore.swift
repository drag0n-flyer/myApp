import Foundation

/// In-memory chat store driven by `SampleData`.
@MainActor
public final class SampleChatStore: ChatStore {
    public var onChange: (@MainActor () -> Void)?
    private var verifiedChatIDs: Set<String> = []
    public private(set) var me: Contact
    public var myStoredName: String { me.displayName }

    public func setMyDisplayName(_ name: String) {
        me.displayName = name
        onChange?()
    }
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
        ChatListOrder.sorted(allChats
            .filter { !$0.isRequest && !$0.archived }
            .filter { chat in
                switch folder.kind {
                case .all: true
                case .unread: chat.unreadCount > 0
                case .custom: chat.folderIDs.contains(folder.id)
                }
            }
        )
    }

    public var archivedChats: [Chat] {
        ChatListOrder.sorted(allChats.filter { !$0.isRequest && $0.archived })
    }

    public var mutedChatIDs: Set<String> { Set(allChats.filter(\.muted).map(\.id)) }

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
        if case .text = content, let i = index(of: chatID) { allChats[i].draft = nil }
        let sentAt = Date()
        let ttl = timers[chatID] ?? 0
        append(Message(id: makeID(), senderID: me.id, sentAt: sentAt, content: content, status: .sent, replyToID: replyTo, reactions: [],
                       expiresAt: ttl > 0 ? sentAt.addingTimeInterval(TimeInterval(ttl)) : nil), to: chatID)
    }

    // MARK: Disappearing messages (in memory)

    private var timers: [String: Int] = [:]

    public func timerSeconds(_ chatID: String) -> Int { timers[chatID] ?? 0 }

    public func setTimer(_ chatID: String, seconds: Int) {
        guard TimerChoice.from(seconds: seconds) != nil, index(of: chatID) != nil, timerSeconds(chatID) != seconds else { return }
        timers[chatID] = seconds
        append(Message(id: makeID(), senderID: "system", sentAt: Date(), content: .system(.timer(seconds: seconds, name: nil)), status: .sent,
                       replyToID: nil, reactions: []), to: chatID)
        onChange?()
    }

    @discardableResult
    public func sweepExpired(now: Date) -> [String] {
        var removed: [String] = []
        for i in allChats.indices {
            let gone = allChats[i].messages.filter { $0.expiresAt.map { $0 <= now } ?? false }.map(\.id)
            guard !gone.isEmpty else { continue }
            allChats[i].messages.removeAll { gone.contains($0.id) }
            removed += gone
        }
        if !removed.isEmpty { onChange?() }
        return removed
    }

    public func nextExpiry() -> Date? { allChats.flatMap(\.messages).compactMap(\.expiresAt).min() }

    /// How long a sample upload stays "uploading" (with its Cancel control) before it counts as sent.
    public var sampleUploadDuration: Duration = .seconds(6)

    public func sendAttachment(_ fileURL: URL, kind: MediaKind, in chatID: String) {
        let bytes = Int64((try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        let content: MessageContent = switch kind {
        case .photo: .photo(bytes: bytes)
        case .voice: .voice(duration: 0)
        case .video, .file: .file(name: fileURL.lastPathComponent, bytes: bytes)
        }
        guard kind != .voice else { send(content, in: chatID, replyTo: nil); return }
        // Like a real upload: the row is "sending" with a fraction and a Cancel, then becomes sent.
        let id = makeID()
        append(Message(id: id, senderID: me.id, sentAt: Date(), content: content, status: .sending, replyToID: nil, reactions: [],
                       uploadProgress: 0.4), to: chatID)
        onChange?()
        let wait = sampleUploadDuration
        Task { [weak self] in
            try? await Task.sleep(for: wait)
            self?.finishSampleUpload(id, in: chatID)
        }
    }

    private func finishSampleUpload(_ messageID: String, in chatID: String) {
        guard let c = index(of: chatID), let m = allChats[c].messages.firstIndex(where: { $0.id == messageID }),
              allChats[c].messages[m].status == .sending else { return }
        allChats[c].messages[m].status = .sent
        allChats[c].messages[m].uploadProgress = nil
        onChange?()
    }

    public func cancelUpload(messageID: String, in chatID: String) {
        guard let c = index(of: chatID), let m = allChats[c].messages.firstIndex(where: { $0.id == messageID }),
              allChats[c].messages[m].senderID == me.id,
              UploadCancel.onCancel(allChats[c].messages[m].status == .sending ? .uploading : .sent) == .removeUnsent else { return }
        allChats[c].messages.remove(at: m)
        onChange?()
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
                hits.append(MessageHit(chatID: chat.id, messageID: message.id, preview: message.previewText, sentAt: message.sentAt))
                if hits.count == limit { return hits }
            }
        }
        return hits
    }

    public var supportsSafetyNumbers: Bool { true }

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
        if let contactID = allChats[i].contactID, let sender = requestSenders.removeValue(forKey: contactID) {
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

    public func unblock(contactID: String) {
        defer { onChange?() }
        blockedIDs.remove(contactID)
    }

    public func report(chatID: String) {
        defer { onChange?() }
        reportedChatIDs.insert(chatID)
    }

    public func markRead(chatID: String) {
        defer { onChange?() }
        if let i = index(of: chatID) { allChats[i].unreadCount = 0 }
    }

    @discardableResult
    public func setPinned(_ chatID: String, _ pinned: Bool) -> Bool {
        guard let i = index(of: chatID) else { return false }
        if !pinned {
            allChats[i].pinnedAt = nil
        } else if allChats[i].pinnedAt == nil {
            guard !allChats[i].archived, allChats.filter({ $0.pinnedAt != nil }).count < ChatListOrder.maxPins else { return false }
            allChats[i].pinnedAt = Date()
        }
        onChange?()
        return true
    }

    public func setMuted(_ chatID: String, _ muted: Bool) {
        defer { onChange?() }
        if let i = index(of: chatID) { allChats[i].muted = muted }
    }

    public func setArchived(_ chatID: String, _ archived: Bool) {
        defer { onChange?() }
        guard let i = index(of: chatID) else { return }
        allChats[i].archived = archived
        if archived { allChats[i].pinnedAt = nil }
    }

    public func setDraft(_ chatID: String, _ text: String) {
        guard let i = index(of: chatID) else { return }
        allChats[i].draft = text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : text
    }

    /// Drafts live in memory here: leaving a chat only has to refresh the list.
    public func flushDrafts() { onChange?() }

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

    public func createGroup(accounts: [String], name: String, photo: Data?) async -> GroupCreateOutcome {
        guard let title = GroupNameRule.clean(name) else { return .failed(.invalidName) }
        let others = Array(Set(accounts).subtracting([me.id]))
        guard others.count >= GroupCreateRules.minPeople else { return .failed(.tooFewPeople) }
        guard others.count <= GroupCreateRules.maxPeople else { return .failed(.tooManyPeople) }
        defer { onChange?() }
        let id = ChatID.group(makeID())
        allChats.append(Chat(id: id, contactID: nil, encryption: .endToEnd, folderIDs: [], unreadCount: 0, messages: [],
                             isRequest: false, title: title))
        return .created(chatID: id, notAdded: [])
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
