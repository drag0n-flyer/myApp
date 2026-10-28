import AppCore
import Foundation
import SecureMessagingCore
import LocalStore
import ServerKit

/// The real `ChatStore` (Design 14): a main-actor cache of the chats and their latest messages, so the
/// synchronous reads stay synchronous. Every write goes to the database first; then the cache is reloaded
/// and `onChange` is called. Network work (sending, blocking, reporting, attachments) runs in background tasks
/// that reload the cache again when they finish.
///
/// The UI-facing methods cannot throw, so a failed database write or network call leaves the store as it was.
/// Never logs message text, names or keys.
@MainActor
public final class LiveChatStore: ChatStore {
    public var onChange: (@MainActor () -> Void)?

    /// How many of a chat's newest messages the cache holds.
    public static let cachedMessageCount = 50
    static let maxTextLength = 4096
    static let reportedMessageCount = 10

    let deps: MessagingDeps
    private let outbox: Outbox
    let inbox: InboxProcessor
    let conversations: ConversationManaging
    private let attachments: AttachmentService?
    private var repo: Repository { deps.repo }

    private var meContact: Contact
    private var contactRows: [String: ContactRecord] = [:]
    private var allChats: [Chat] = []
    private var customFolders: [ChatFolder] = []
    private var tasks: [UUID: Task<Void, Never>] = [:]
    private let pendingGate = PendingActions.Gate()
    private var closed = false

    public init(
        deps: MessagingDeps, outbox: Outbox, inbox: InboxProcessor, conversations: ConversationManaging,
        attachments: AttachmentService?
    ) {
        self.deps = deps
        self.outbox = outbox
        self.inbox = inbox
        self.conversations = conversations
        self.attachments = attachments
        meContact = Contact(id: deps.me.accountID, displayName: "You", username: "")
        reload()
        if let attachments {
            // Download progress and finished sends change what the chat shows.
            Task { [weak self] in
                await attachments.setOnChange { Task { @MainActor in self?.refresh() } }
            }
        }
    }

    // MARK: - Cache

    /// Reloads the cache from the database and calls `onChange`.
    public func refresh() {
        reload()
        onChange?()
    }

    /// Fetches and processes the inbox, then refreshes. For pull-to-refresh and for the app's launch sync.
    /// The push-driven `InboxProcessor.run(events:)` has no change callback: call `refresh()` after it reports.
    public func syncInbox() async {
        guard !closed else { return }
        _ = try? await inbox.syncOnce()
        await PendingActions.run(repo: repo, server: deps.server, gate: pendingGate)
        refresh()
    }

    /// Sends what is queued, then refreshes.
    public func flushOutbox() async {
        guard !closed else { return }
        try? await outbox.flush()
        await PendingActions.run(repo: repo, server: deps.server, gate: pendingGate)
        refresh()
    }

    /// Waits for every background task started so far (sends, receipts, block, report, downloads). For tests
    /// and for shutdown.
    public func settle() async {
        while let task = tasks.values.first { await task.value }
    }

    /// Stops the store before its data is wiped (sign-out): later syncs, flushes and background work do nothing,
    /// and every task started so far is cancelled and waited for, so none can write after the wipe.
    public func shutdown() async {
        closed = true
        for task in tasks.values { task.cancel() }
        await settle()
        if let attachments { await attachments.setOnChange {} }
    }

    private func background(_ work: @escaping @Sendable () async -> Void) {
        guard !closed else { return }
        let id = UUID()
        tasks[id] = Task { [weak self] in
            await work()
            self?.tasks[id] = nil
            self?.refresh()
        }
    }

    private func reload() {
        let rows = (try? repo.contacts()) ?? []
        contactRows = Dictionary(rows.map { ($0.accountID, $0) }, uniquingKeysWith: { $1 })
        if let mine = contactRows[deps.me.accountID] {
            meContact = Contact(id: mine.accountID, displayName: mine.displayName, username: mine.username)
        }
        let folderRecords = (try? repo.folders()) ?? []
        customFolders = folderRecords.map { ChatFolder(id: $0.id, name: $0.name, kind: .custom) }
        var folderIDs: [String: Set<String>] = [:]
        for folder in folderRecords {
            for chatID in (try? repo.folderChats(folderID: folder.id)) ?? [] { folderIDs[chatID, default: []].insert(folder.id) }
        }
        allChats = ((try? repo.chats()) ?? []).map { record in
            let all = (try? repo.conversations(chatID: record.id)) ?? []
            let preferred = (try? repo.preferredConversation(chatID: record.id)) ?? all.first
            let latest = (try? repo.messages(chatID: record.id, before: nil, limit: Self.cachedMessageCount)) ?? []
            return ChatMapping.chat(
                record, conversations: all, preferred: preferred, folderIDs: folderIDs[record.id] ?? [], messages: map(latest))
        }
    }

    private func map(_ records: [MessageRecord]) -> [Message] {
        let showSafety = supportsSafetyNumbers
        // The switch is reciprocal: while it is off, others' read receipts (still stored) show as delivered.
        let showRead = outbox.readReceiptsEnabled()
        let me = deps.me.accountID
        return records.map { record in
            let plain = record.senderAccount != ChatMapping.systemSender && !record.id.hasPrefix(ChatMapping.callPrefix)
            return ChatMapping.message(
                record,
                attachment: plain ? (try? repo.attachment(messageID: record.id)) ?? nil : nil,
                reactions: plain && !record.deleted ? (try? repo.reactions(messageID: record.id)) ?? [] : [],
                displayName: { [contactRows] in Self.name(of: contactRows[$0]) },
                showRead: showRead || record.senderAccount != me)
        }
        // "Safety number changed" refers to a screen that is hidden while the numbers are not real.
        .filter { showSafety || $0.content != .system(.safetyNumberChanged) }
    }

    private nonisolated static func name(of row: ContactRecord?) -> String? {
        guard let row else { return nil }
        if !row.displayName.isEmpty { return row.displayName }
        return row.username.isEmpty ? nil : row.username
    }

    private func contact(from row: ContactRecord) -> Contact {
        Contact(id: row.accountID, displayName: Self.name(of: row) ?? "Unknown", username: row.username)
    }

    private func nowMillis() -> Int64 { Int64((deps.now().timeIntervalSince1970 * 1000).rounded()) }

    private func freshID() -> String { UUID().uuidString.lowercased() }

    // MARK: - Reads

    public var me: Contact { meContact }

    public var contacts: [Contact] {
        contactRows.values.filter(\.isContact).sorted { ($0.displayName, $0.accountID) < ($1.displayName, $1.accountID) }
            .map(contact(from:))
    }

    public var folders: [ChatFolder] { [.all, .unread] + customFolders }

    public func chats(in folder: ChatFolder) -> [Chat] {
        allChats.filter { chat in
            guard !chat.isRequest else { return false }
            switch folder.kind {
            case .all: return true
            case .unread: return chat.unreadCount > 0
            case .custom: return chat.folderIDs.contains(folder.id)
            }
        }
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

    public var blockedIDs: Set<String> { Set((try? repo.blockedIDs()) ?? []) }

    public func chat(id: String) -> Chat? { allChats.first { $0.id == id } }

    public func contact(id: String) -> Contact? {
        if id == deps.me.accountID { return meContact }
        return contactRows[id].map(contact(from:))
    }

    public func messages(in chatID: String, before messageID: String?, limit: Int) -> [Message] {
        guard limit > 0 else { return [] }
        var anchor: MessageRecord?
        if let messageID {
            guard let record = (try? repo.message(id: messageID)) ?? nil, record.chatID == chatID else { return [] }
            anchor = record
        }
        return map((try? repo.messages(chatID: chatID, before: anchor, limit: limit)) ?? [])
    }

    public func searchMessages(_ query: String, limit: Int) -> [MessageHit] {
        let folded = fold(query.trimmingCharacters(in: .whitespacesAndNewlines))
        guard !folded.isEmpty, limit > 0 else { return [] }
        let requests = Set(allChats.filter(\.isRequest).map(\.id))
        let found = (try? repo.search(folded, limit: limit)) ?? []
        return found.compactMap { hit in
            guard !requests.contains(hit.chatID), let record = (try? repo.message(id: hit.messageID)) ?? nil else { return nil }
            let preview = map([record]).first?.previewText ?? ""
            return MessageHit(chatID: hit.chatID, messageID: hit.messageID, preview: preview)
        }
    }

    // MARK: - Sending

    public func send(_ content: MessageContent, in chatID: String, replyTo: String?) {
        guard case .text(let raw) = content else { return }
        let text = String(raw.prefix(Self.maxTextLength))
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        enqueue(.text(text, replyTo: replyTo), chatID: chatID)
    }

    private func enqueue(_ body: ContentEnvelope.Body, chatID: String) {
        guard ensureChatRow(chatID) != nil else { return }
        let envelope = ContentEnvelope(id: freshID(), at: nowMillis(), body: body)
        do {
            try outbox.enqueue(envelope, chatID: chatID)
            bumpActivity(chatID, at: deps.now())
        } catch { return }
        refresh()   // the message shows as `sending` now
        let outbox = outbox
        background { try? await outbox.flush() }
    }

    /// Messages that gave up sending (10 attempts or 24 hours). AppCore has no `failed` delivery status yet, so
    /// they show as `sending`; the UI can use this to offer a retry.
    public func failedMessageIDs(in chatID: String) -> Set<String> {
        let rows = (try? repo.messages(chatID: chatID, before: nil, limit: 200)) ?? []
        return Set(rows.filter { $0.status == "failed" && $0.senderAccount == deps.me.accountID }.map(\.id))
    }

    /// Puts a failed message at the end of its chat's queue and sends it.
    public func retrySend(messageID: String) {
        guard (try? outbox.retry(messageID: messageID)) != nil else { return }
        refresh()
        let outbox = outbox
        background { try? await outbox.flush() }
    }

    public func sendAttachment(_ fileURL: URL, kind: MediaKind, in chatID: String) {
        guard let attachments, ensureChatRow(chatID) != nil else { return }
        let outbox = outbox
        background {
            do {
                try await attachments.send(file: fileURL, kind: kind, chatID: chatID, replyTo: nil)
                try await outbox.flush()
            } catch {}
        }
    }

    public func download(messageID: String, in chatID: String) {
        guard let attachments, let record = (try? repo.attachment(messageID: messageID)) ?? nil else { return }
        let id = record.id
        background { try? await attachments.download(attachmentID: id) }
    }

    // MARK: - Reactions, edits, deletes

    public func react(_ emoji: String, to messageID: String, in chatID: String) {
        guard let record = (try? repo.message(id: messageID)) ?? nil, record.chatID == chatID, !record.deleted,
              record.senderAccount != ChatMapping.systemSender, !record.id.hasPrefix(ChatMapping.callPrefix) else { return }
        let mine = ((try? repo.reactions(messageID: messageID)) ?? []).first { $0.accountID == deps.me.accountID }?.emoji
        // The same emoji again takes the reaction back.
        let chosen = mine == emoji ? "" : emoji
        guard (try? repo.setReaction(messageID: messageID, accountID: deps.me.accountID, emoji: chosen)) != nil else { return }
        refresh()
        sendControl(.react(target: messageID, emoji: chosen), chatID: chatID)
    }

    public func edit(messageID: String, in chatID: String, text: String) {
        let newText = String(text.prefix(Self.maxTextLength))
        guard !newText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let record = (try? repo.message(id: messageID)) ?? nil, record.chatID == chatID,
              record.senderAccount == deps.me.accountID, !record.deleted,
              case .text(_, let reply) = ContentEnvelope.decode(record.envelope).body else { return }
        let old = ContentEnvelope.decode(record.envelope)
        let edited = ContentEnvelope(id: old.id, at: old.at, body: .text(newText, replyTo: reply)).encoded()
        guard (try? repo.applyEdit(id: messageID, sender: deps.me.accountID, envelope: edited, folded: fold(newText), at: deps.now())) == true
        else { return }
        refresh()
        sendControl(.edit(target: messageID, text: newText), chatID: chatID)
    }

    public func delete(messageID: String, in chatID: String) {
        guard let record = (try? repo.message(id: messageID)) ?? nil, record.chatID == chatID,
              record.senderAccount == deps.me.accountID, !record.deleted,
              !record.id.hasPrefix(ChatMapping.callPrefix) else { return }
        guard (try? repo.markDeleted(id: messageID, sender: deps.me.accountID)) == true else { return }
        refresh()
        sendControl(.delete(target: messageID), chatID: chatID)
    }

    /// Queued in the chat's outbox, behind the message it refers to; retried like a message.
    private func sendControl(_ body: ContentEnvelope.Body, chatID: String) {
        guard (try? outbox.enqueueControl(body, chatID: chatID)) != nil else { return }
        let outbox = outbox
        background { try? await outbox.flush() }
    }

    // MARK: - Calls

    public func recordCall(_ summary: CallSummary, contactID: String, outgoing: Bool, at date: Date) {
        guard let chatID = ensureChatRow(contactID),
              let envelope = try? JSONEncoder().encode(summary) else { return }
        let id = ChatMapping.callPrefix + freshID()
        let (video, outcome, duration): (Bool, String, Double) = switch summary {
        case .missedAudio: (false, "missed", 0)
        case .missedVideo: (true, "missed", 0)
        case .audio(let d): (false, "completed", d)
        case .video(let d): (true, "completed", d)
        }
        do {
            try repo.transaction {
                try repo.insertCall(CallRecord(
                    id: id, chatID: chatID, video: video, outgoing: outgoing, outcome: outcome, startedAt: date, duration: duration))
                try repo.insertMessage(MessageRecord(
                    id: id, chatID: chatID, conversationID: nil, senderAccount: outgoing ? deps.me.accountID : contactID,
                    senderDevice: nil, sentAt: date, receivedAt: deps.now(), envelope: envelope,
                    status: outgoing ? "sent" : "delivered", replyTo: nil, editedAt: nil, deleted: false,
                    undecryptable: false, folded: ""))
            }
            bumpActivity(chatID, at: date)
        } catch { return }
        refresh()
    }

    public func incomingCall(from caller: Contact, video: Bool) -> IncomingCallDecision {
        if (try? repo.isBlocked(caller.id)) == true { return .silentMissed }
        if contactRows[caller.id]?.isContact == true { return .ring }
        if contactRows[caller.id] == nil {
            try? repo.upsertContact(ContactRecord(
                accountID: caller.id, username: caller.username, displayName: caller.displayName, isContact: false,
                addedAt: deps.now()))
        }
        recordCall(video ? .missedVideo : .missedAudio, contactID: caller.id, outgoing: false, at: deps.now())
        return .silentMissed
    }

    // MARK: - Safety number

    /// One flag, tied to the engine: safety numbers are shown only when the engine's member keys are real
    /// (otherwise the number would not cover any key). TODO(M7): flips by itself when the engine reports it.
    public var supportsSafetyNumbers: Bool { deps.mls.providesRealMemberKeys }

    public func safetyNumber(for chatID: String) -> SafetyNumber? {
        guard supportsSafetyNumbers else { return nil }
        guard let row = (try? repo.safety(chatID: chatID)) ?? nil else { return nil }
        return SafetyNumber(groups: ChatMapping.groups(row.current), isVerified: row.verified == row.current)
    }

    /// Local only: nothing is sent.
    public func setVerified(_ verified: Bool, chatID: String) {
        guard supportsSafetyNumbers else { return }
        guard let row = (try? repo.safety(chatID: chatID)) ?? nil else { return }
        try? repo.setVerified(chatID: chatID, verified ? row.current : nil)
        refresh()
    }

    // MARK: - Requests, contacts, blocking, reports

    /// Only used by the sample store's simulated incoming messages. Real messages arrive through the inbox.
    public func receive(_ content: MessageContent, from contact: Contact) {}

    public func accept(requestID: String) {
        guard let chat = (try? repo.chat(id: requestID)) ?? nil, chat.isRequest else { return }
        do {
            try repo.transaction {
                try repo.setRequest(chatID: requestID, false)
                var row = (try repo.contact(id: requestID)) ?? ContactRecord(
                    accountID: requestID, username: "", displayName: "", isContact: true, addedAt: deps.now())
                row.isContact = true
                try repo.upsertContact(row)
            }
        } catch { return }
        refresh()
        let read = peerMessageIDs(requestID) { $0.status == "read" }
        if !read.isEmpty, outbox.readReceiptsEnabled() { sendControl(.receipt(.read, ids: read), chatID: requestID) }
    }

    public func block(contactID: String) {
        do {
            try repo.transaction {
                try repo.block(contactID)
                try repo.deleteChat(id: contactID)
                if var row = try repo.contact(id: contactID) {
                    row.isContact = false
                    try repo.upsertContact(row)
                }
                try repo.set(PendingActions.blockPrefix + contactID, Data([1]))
            }
        } catch { return }
        refresh()
        kickPending()
    }

    public func report(chatID: String) {
        let records = (try? repo.messages(chatID: chatID, before: nil, limit: 40)) ?? []
        let lines = map(records).filter { message in
            if case .system = message.content { return false }
            return true
        }.suffix(Self.reportedMessageCount).map { ReportedMessage(sentAt: $0.sentAt, text: $0.previewText) }
        let pending = PendingActions.Report(
            accountID: chatID, conversationID: ((try? repo.preferredConversation(chatID: chatID)) ?? nil)?.serverID,
            messages: Array(lines))
        guard let data = try? JSONEncoder().encode(pending),
              (try? repo.set(PendingActions.reportPrefix + freshID(), data)) != nil else { return }
        kickPending()
    }

    /// Sends block and report requests that are still pending, in the background.
    private func kickPending() {
        let repo = repo, server = deps.server, gate = pendingGate
        background { await PendingActions.run(repo: repo, server: server, gate: gate) }
    }

    public func markRead(chatID: String) {
        guard let chat = (try? repo.chat(id: chatID)) ?? nil else { return }
        let ids = unreadPeerMessageIDs(chatID)
        guard chat.unread > 0 || !ids.isEmpty else { return }
        try? repo.setUnread(chatID: chatID, 0)
        try? repo.updateStatus(ids: ids, to: "read")
        refresh()
        // A message request gets no receipt until it is accepted (`accept` sends it). With the Read receipts
        // switch off nothing is sent, now or later: the messages are marked read here all the same.
        if !chat.isRequest, !ids.isEmpty, outbox.readReceiptsEnabled() {
            sendControl(.receipt(.read, ids: ids), chatID: chatID)
        }
    }

    private func unreadPeerMessageIDs(_ chatID: String) -> [String] {
        peerMessageIDs(chatID) { $0.status != "read" }
    }

    private func peerMessageIDs(_ chatID: String, where include: (MessageRecord) -> Bool) -> [String] {
        ((try? repo.messages(chatID: chatID, before: nil, limit: 500)) ?? []).filter {
            $0.senderAccount != deps.me.accountID && $0.senderAccount != ChatMapping.systemSender
                && !$0.id.hasPrefix(ChatMapping.callPrefix) && !$0.undecryptable && include($0)
        }.map(\.id)
    }

    public func addContact(username: String) async -> Contact? {
        let name = username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !name.isEmpty, let user = try? await deps.server.lookUpUser(username: name) else { return nil }
        let row = ContactRecord(
            accountID: user.accountID, username: user.username, displayName: user.displayName, isContact: true,
            addedAt: contactRows[user.accountID]?.addedAt ?? deps.now())
        guard (try? repo.upsertContact(row)) != nil else { return nil }
        refresh()
        return contact(from: row)
    }

    /// The chat with this contact, created empty if there is none yet. A chat with a non-contact is a request.
    public func ensureChat(with contactID: String) -> String {
        let id = ensureChatRow(contactID) ?? contactID
        refresh()
        return id
    }

    /// Makes sure the contact row and the chat row exist. `nil` when the database write failed.
    private func ensureChatRow(_ contactID: String) -> String? {
        do {
            if try repo.chat(id: contactID) != nil { return contactID }
            let known = try repo.contact(id: contactID)
            if known == nil {
                try repo.upsertContact(ContactRecord(
                    accountID: contactID, username: "", displayName: "", isContact: false, addedAt: deps.now()))
            }
            try repo.upsertChat(ChatRecord(
                id: contactID, isRequest: !(known?.isContact ?? false), unread: 0, muted: false, lastActivity: deps.now()))
            return contactID
        } catch { return nil }
    }

    private func bumpActivity(_ chatID: String, at date: Date) {
        guard var chat = (try? repo.chat(id: chatID)) ?? nil, date > chat.lastActivity else { return }
        chat.lastActivity = date
        try? repo.upsertChat(chat)
    }

    // MARK: - Folders

    public func addFolder(name: String, chatIDs: Set<String>) -> ChatFolder {
        let folder = ChatFolder(id: "folder-\(freshID())", name: name, kind: .custom)
        do {
            try repo.transaction {
                try repo.addFolder(FolderRecord(id: folder.id, name: name, position: try repo.folders().count))
                try repo.setFolderChats(folderID: folder.id, chatIDs: chatIDs.sorted())
            }
        } catch {}
        refresh()
        return folder
    }

    public func renameFolder(id: String, to name: String) {
        try? repo.renameFolder(id: id, name: name)
        refresh()
    }

    public func deleteFolder(id: String) {
        try? repo.deleteFolder(id: id)
        refresh()
    }

    public func moveFolder(id: String, toCustomIndex index: Int) {
        try? repo.moveFolder(id: id, to: index)
        refresh()
    }
}
