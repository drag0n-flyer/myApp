import AppCore
import Foundation
import SecureMessagingCore
import LocalStore
import ServerKit
#if canImport(UniformTypeIdentifiers)
import UniformTypeIdentifiers
#endif

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
    /// A report carries what was typed, not the plain display form.
    static func storedText(_ message: Message) -> String {
        if case .text(let text) = message.content { return text }
        return message.previewText
    }

    let deps: MessagingDeps
    let outbox: Outbox
    let inbox: InboxProcessor
    let conversations: ConversationManaging
    private let attachments: AttachmentService?
    private var repo: Repository { deps.repo }

    private var meContact: Contact
    private var contactRows: [String: ContactRecord] = [:]
    /// What contacts said about themselves (`profile`), by account, read at every reload.
    var profileRows: [String: StoredProfile] = [:]
    /// Tests only: replaces how the own profile is queued for a chat.
    var profileEnqueueOverride: ((ContentEnvelope.Body, String) throws -> Void)?
    private var allChats: [Chat] = []
    /// Draft texts not yet in the database: typed before the chat had a row, or a write that failed. They are laid
    /// over the saved drafts on every reload and written as soon as the row exists.
    private var draftsPending: [String: String] = [:]
    /// Typing waits this long before a draft is written. Tests shorten it.
    var draftWriteDelay: Duration = .milliseconds(500)
    private var draftTask: Task<Void, Never>?
    private var customFolders: [ChatFolder] = []
    private var tasks: [UUID: Task<Void, Never>] = [:]
    private let pendingGate = PendingActions.Gate()
    private var closed = false
    var isClosed: Bool { closed }
    private var rebaselined: Set<String> = []
    /// Messages whose attachment upload is running now. A second "Try again" while one runs does nothing.
    private var uploading: Set<String> = []
    /// How far each running upload has come (0...1), shown as the ring on the bubble.
    private var uploadFraction: [String: Double] = [:]
    /// The background task of each running upload, so Cancel can stop it.
    private var uploadTasks: [String: UUID] = [:]
    /// Uploads the server refused for their size; their failed rows say so while the app runs.
    private var tooBig: Set<String> = []
    /// The newest time given to a message of a batch, so a batch keeps its order within one millisecond.
    private var lastStamp = Date.distantPast

    public init(
        deps: MessagingDeps, outbox: Outbox, inbox: InboxProcessor, conversations: ConversationManaging,
        attachments: AttachmentService?
    ) {
        self.deps = deps
        self.outbox = outbox
        self.inbox = inbox
        self.conversations = conversations
        self.attachments = attachments
        meContact = Contact(id: deps.me.accountID, displayName: Self.placeholderName, username: "")
        recoverPendingUploads()
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
        await installAutoDownload()
        await installGroupSync()
        _ = try? await inbox.syncOnce()
        // A chat whose first commit's reply was lost is finished here, never started again (MLS profile, step 5).
        await conversations.resumePendingCreates()
        await PendingActions.run(repo: repo, server: deps.server, gate: pendingGate)
        refresh()
    }

    /// New messages from others start their attachment download when the policy allows it (Wi-Fi, voice).
    /// The download runs as a store task, so the inbox drain never waits for it.
    private func installAutoDownload() async {
        guard let attachments else { return }
        await inbox.setOnStored { [weak self] message in
            await MainActor.run {
                _ = self?.background { await attachments.autoDownload(for: message) }
            }
        }
    }

    /// Every group this device is in is read from the server once and brought in line, so a missed `members` item is caught
    /// up. The app calls it at start-up and on each live connect (not on every inbox event: it is one request per group).
    public func reconcileGroups() async {
        guard !closed else { return }
        await conversations.reconcileGroups()
        flushOutboxSoon()
        refresh()
    }

    /// A `members` item starts a group sync in the background (it may read the inbox itself, so the drain never waits for it).
    private func installGroupSync() async {
        let manager = conversations
        await inbox.setOnMembers { [weak self] id, actor in
            Task { @MainActor in
                await manager.groupMembersChanged(conversationID: id, actor: actor)
                self?.flushOutboxSoon()
                self?.refresh()
            }
        }
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
        draftTask?.cancel()
        draftsPending.removeAll()
        for task in tasks.values { task.cancel() }
        await settle()
        if let attachments { await attachments.setOnChange {} }
    }

    @discardableResult
    private func background(_ work: @escaping @Sendable () async -> Void) -> UUID? {
        guard !closed else { return nil }
        let id = UUID()
        tasks[id] = Task { [weak self] in
            await work()
            self?.tasks[id] = nil
            self?.refresh()
        }
        return id
    }

    /// The time for a new message: a millisecond after the one before it while the clock has not passed it (a
    /// burst keeps its order), and the clock itself otherwise, so a clock that jumped ahead and was corrected is
    /// followed again instead of every later message carrying a time in the future.
    private func stamp() -> Date {
        let stamped = Self.nextStamp(clock: deps.now(), last: lastStamp)
        lastStamp = stamped
        return stamped
    }

    nonisolated static func nextStamp(clock: Date, last: Date) -> Date {
        let ahead = last.timeIntervalSince(clock)
        return ahead >= 0 && ahead <= 1 ? last.addingTimeInterval(0.001) : clock
    }

    private func reload() {
        let rows = (try? repo.contacts()) ?? []
        contactRows = Dictionary(rows.map { ($0.accountID, $0) }, uniquingKeysWith: { $1 })
        // A message request is not shown by its profile (name or photo) until it is accepted.
        profileRows = Dictionary(((try? repo.profiles()) ?? []).filter { contactRows[$0.accountID]?.isContact == true }.map {
            ($0.accountID, StoredProfile(accountID: $0.accountID, name: $0.name, photo: $0.photo, ver: $0.ver))
        }, uniquingKeysWith: { $1 })
        if let mine = contactRows[deps.me.accountID] {
            // No stored name shows the placeholder; the placeholder itself is never stored.
            let name = DisplayNameRule.forDisplay(mine.displayName, fallback: "")
            meContact = Contact(id: mine.accountID, displayName: name.isEmpty ? Self.placeholderName : name, username: mine.username)
        }
        let folderRecords = (try? repo.folders()) ?? []
        customFolders = folderRecords.map { ChatFolder(id: $0.id, name: $0.name, kind: .custom) }
        var folderIDs: [String: Set<String>] = [:]
        for folder in folderRecords {
            for chatID in (try? repo.folderChats(folderID: folder.id)) ?? [] { folderIDs[chatID, default: []].insert(folder.id) }
        }
        // An expired message that is not swept yet no longer counts as unread.
        let expiredUnread = (try? repo.expiredUnreadCounts(me: deps.me.accountID)) ?? [:]
        let records = ((try? repo.chats()) ?? []).map { record -> ChatRecord in
            var record = record
            record.unread = max(0, record.unread - (expiredUnread[record.id] ?? 0))
            return record
        }
        for id in draftsPending.keys where records.contains(where: { $0.id == id }) { persistDraft(id) }
        var drafts = (try? repo.drafts()) ?? [:]
        for (id, text) in draftsPending { drafts[id] = text.isEmpty ? nil : text }
        allChats = records.map { record in
            let all = (try? repo.conversations(chatID: record.id)) ?? []
            let preferred = (try? repo.preferredConversation(chatID: record.id)) ?? all.first
            let latest = (try? repo.messages(chatID: record.id, before: nil, limit: Self.cachedMessageCount)) ?? []
            return ChatMapping.chat(
                record, conversations: all, preferred: preferred, folderIDs: folderIDs[record.id] ?? [], messages: map(latest),
                draft: drafts[record.id])
        }
        sendOwnProfileIfNeeded()
    }

    private func map(_ records: [MessageRecord]) -> [Message] {
        let showSafety = supportsSafetyNumbers
        // The switch is reciprocal: while it is off, others' read receipts (still stored) show as delivered.
        let showRead = outbox.readReceiptsEnabled()
        let me = deps.me.accountID
        return records.map { record -> Message in
            let plain = record.senderAccount != ChatMapping.systemSender && !record.id.hasPrefix(ChatMapping.callPrefix)
            var message = ChatMapping.message(
                record,
                attachment: plain ? (try? repo.attachment(messageID: record.id)) ?? nil : nil,
                reactions: plain && !record.deleted ? (try? repo.reactions(messageID: record.id)) ?? [] : [],
                displayName: { [contactRows, profileRows] id in
                    guard let row = contactRows[id] else { return nil }
                    let name = ProfileDisplay.name(lookup: Self.name(of: row) ?? "", profile: profileRows[id], nickname: nil)
                    return name.isEmpty ? nil : name
                },
                showRead: showRead || record.senderAccount != me)
            if record.status == "failed", tooBig.contains(record.id) { message.sendFailure = .tooBig }
            if record.status == "sending", uploading.contains(record.id) { message.uploadProgress = uploadFraction[record.id] ?? 0 }
            return message
        }
        // "Safety number changed" refers to a screen that is hidden while the numbers are not real.
        .filter { showSafety || $0.content != .system(.safetyNumberChanged) }
    }

    /// Display only: shown while no own name is stored. Never stored, never sent.
    static let placeholderName = "You"

    private nonisolated static func name(of row: ContactRecord?) -> String? {
        guard let row else { return nil }
        // Names come from the server and from other people's devices: cleaned for every screen.
        let name = DisplayNameRule.forDisplay(row.displayName, fallback: row.username)
        return name.isEmpty ? nil : name
    }

    private func contact(from row: ContactRecord) -> Contact {
        // The name the person sent about themselves wins over the lookup name (no local nicknames exist yet).
        Contact(id: row.accountID,
                displayName: ProfileDisplay.name(lookup: Self.name(of: row) ?? "Unknown", profile: profileRows[row.accountID], nickname: nil),
                username: row.username)
    }

    private func nowMillis() -> Int64 { Int64((deps.now().timeIntervalSince1970 * 1000).rounded()) }

    private func freshID() -> String { UUID().uuidString.lowercased() }

    // MARK: - Reads

    public var me: Contact { meContact }

    public var myStoredName: String { contactRows[deps.me.accountID]?.displayName ?? "" }

    /// Shown at once and stored in the own contact row (never as a contact, never as the placeholder).
    public func setMyDisplayName(_ name: String) {
        let id = deps.me.accountID
        var row = contactRows[id] ?? ((try? repo.contact(id: id)) ?? nil)
            ?? ContactRecord(accountID: id, username: meContact.username, displayName: name, isContact: false, addedAt: deps.now())
        row.displayName = name
        guard (try? repo.upsertContact(row)) != nil else { return }
        refresh()
    }

    public var contacts: [Contact] {
        contactRows.values.filter(\.isContact).sorted { ($0.displayName, $0.accountID) < ($1.displayName, $1.accountID) }
            .map(contact(from:))
    }

    public var folders: [ChatFolder] { [.all, .unread] + customFolders }

    public func chats(in folder: ChatFolder) -> [Chat] {
        // `allChats` is in activity order; pinned chats move to the front, the newest pin first.
        ChatListOrder.pinnedFirst(allChats.filter { chat in
            guard !chat.isRequest, !chat.archived else { return false }
            switch folder.kind {
            case .all: return true
            case .unread: return chat.unreadCount > 0
            case .custom: return chat.folderIDs.contains(folder.id)
            }
        })
    }

    public var archivedChats: [Chat] { ChatListOrder.pinnedFirst(allChats.filter { !$0.isRequest && $0.archived }) }

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
        search(query, chatID: nil, limit: limit)
    }

    /// Searched inside the database, so a chat's hits are never crowded out by newer hits in other chats.
    public func searchMessages(_ query: String, in chatID: String, limit: Int) -> [MessageHit] {
        search(query, chatID: chatID, limit: limit)
    }

    private func search(_ query: String, chatID: String?, limit: Int) -> [MessageHit] {
        let folded = fold(query.trimmingCharacters(in: .whitespacesAndNewlines))
        guard !folded.isEmpty, limit > 0 else { return [] }
        let requests = Set(allChats.filter(\.isRequest).map(\.id))
        let found = (try? repo.search(folded, chatID: chatID, limit: limit)) ?? []
        return found.compactMap { hit in
            // The global search leaves message requests out; a chat the person has open is searched as asked.
            guard chatID != nil || !requests.contains(hit.chatID),
                  let record = (try? repo.message(id: hit.messageID)) ?? nil else { return nil }
            let preview = map([record]).first?.previewText ?? ""
            return MessageHit(chatID: hit.chatID, messageID: hit.messageID, preview: preview, sentAt: record.sentAt)
        }
    }

    // MARK: - Sending

    public func send(_ content: MessageContent, in chatID: String, replyTo: String?) {
        send(content, in: chatID, replyTo: replyTo, preview: nil)
    }

    /// The preview goes along only when it is valid for this text (an https link found in the text, a title, a small
    /// JPEG); otherwise the text is sent alone.
    public func send(_ content: MessageContent, in chatID: String, replyTo: String?, preview card: LinkPreviewCard?) {
        guard case .text(let raw) = content else { return }
        let text = String(raw.prefix(Self.maxTextLength))
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let preview = card.map { LinkPreview(url: $0.url, title: $0.title, desc: $0.desc, img: $0.image) }.flatMap { $0.wire.valid(in: text) ? $0.wire.preview : nil }
        enqueue(.text(text, replyTo: replyTo), chatID: chatID, preview: preview)
    }

    private func enqueue(_ body: ContentEnvelope.Body, chatID: String, preview: LinkPreview? = nil) {
        guard ensureChatRow(chatID) != nil else { return }
        let at = stamp()
        let envelope = ContentEnvelope(
            id: freshID(), at: Int64((at.timeIntervalSince1970 * 1000).rounded()), body: body, ttl: currentTTL(chatID), preview: preview)
        do {
            try outbox.enqueue(envelope, chatID: chatID)
            bumpActivity(chatID, at: at)
            // The message is queued: its draft is done. (Nothing queued, nothing cleared.)
            if case .text = body { setDraftQuietly(chatID) }
        } catch { return }
        refresh()   // the message shows as `sending` now
        let outbox = outbox
        background { try? await outbox.flush() }
    }

    /// Sends a failed message again. An attachment that was never uploaded uploads again first; a message that is
    /// already queued goes to the end of its chat's queue. Does nothing for a message that is not failed, so a
    /// second tap cannot send twice.
    public func retrySend(messageID: String) {
        if let folder = pendingFolder(messageID), FileManager.default.fileExists(atPath: folder.appendingPathComponent(PendingUpload.metaFileName).path) {
            guard !uploading.contains(messageID), (try? repo.resumeUnsent(id: messageID)) == true else { return }
            refresh()   // shows "Sending…" again
            startUpload(messageID)
            return
        }
        do {
            guard try outbox.retry(messageID: messageID) else { return }
        } catch is AttachmentServiceError {
            // A placeholder whose upload folder is gone can never be sent.
            _ = try? repo.deleteUnsentMessage(id: messageID, sender: deps.me.accountID)
            refresh()
            return
        } catch { return }
        refresh()
        let outbox = outbox
        background { try? await outbox.flush() }
    }

    public func sendAttachment(_ fileURL: URL, kind: MediaKind, in chatID: String) {
        sendMedia(fileURL, kind: kind, duration: 0, chatID: chatID)
    }

    public func sendAttachment(_ fileURL: URL, kind: MediaKind, caption: String?, in chatID: String) {
        sendMedia(fileURL, kind: kind, duration: 0, chatID: chatID, caption: Self.cleanCaption(caption))
    }

    /// Trimmed, cut to 4,096 characters, `nil` when blank.
    static func cleanCaption(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(ContentEnvelope.maxCaptionLength))
    }

    public func sendVoice(_ fileURL: URL, duration: Double, in chatID: String) {
        sendMedia(fileURL, kind: .voice, duration: duration, chatID: chatID)
    }

    // MARK: - Attachments

    private func pendingFolder(_ messageID: String) -> URL? {
        PendingUpload.folder(in: deps.mediaDirectory, messageID: messageID)
    }

    /// The message shows at once as "sending" with its real name and size. The picked file is copied into
    /// `media/pending/<id>/` with a small description, and the row gets a placeholder envelope (no attachment ID yet)
    /// that is never queued. The upload then runs in the background (`AttachmentService.uploadPending`): a failure
    /// makes the row "failed" with the files kept, and "Try again" really uploads again.
    private func sendMedia(_ fileURL: URL, kind: MediaKind, duration: Double, chatID: String, caption: String? = nil,
                           then: (@MainActor (_ staged: Bool) -> Void)? = nil) {
        guard attachments != nil, !closed, ensureChatRow(chatID) != nil else { then?(false); return }
        let id = freshID()
        guard let folder = pendingFolder(id) else { then?(false); return }
        let kindName: String
        switch kind {
        case .photo: kindName = "photo"
        case .voice: kindName = "voice"
        case .file, .video: kindName = "file"
        }
        let name = MediaFileName.clean(fileURL.lastPathComponent)
        let sourceName = "source." + (AttachmentID.safeExtension(of: name) ?? "bin")
        let meta = PendingUpload(kind: kindName, chatID: chatID, name: name, sourceName: sourceName,
                                 mime: Self.mimeType(of: fileURL, kind: kind), duration: duration)
        // The copy of a large file happens off the main actor; the row appears when it is done. A crash before
        // that leaves a folder with no row, which the next start removes.
        background { [weak self] in
            let fm = FileManager.default
            let staged = folder.appendingPathComponent(sourceName)
            let size: Int64
            do {
                try fm.createDirectory(at: folder, withIntermediateDirectories: true)
                try fm.copyItem(at: fileURL, to: staged)
                size = Int64(try staged.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0)
                try meta.write(to: folder)
            } catch {
                PendingUpload.removeFolder(folder)
                await self?.stagingDone(then, staged: false)
                return
            }
            let ok = await self?.stagedForUpload(id: id, meta: meta, size: size, staged: staged, folder: folder, caption: caption) ?? false
            await self?.stagingDone(then, staged: ok)
        }
    }

    private func stagingDone(_ then: (@MainActor (_ staged: Bool) -> Void)?, staged: Bool) { then?(staged) }

    /// The picked file is staged: the message shows as "sending" with its real name and size, and the upload starts.
    /// True when the row is in and the upload started.
    private func stagedForUpload(id: String, meta: PendingUpload, size: Int64, staged: URL, folder: URL, caption: String? = nil) -> Bool {
        guard !closed else { PendingUpload.removeFolder(folder); return false }
        let placeholder = Att(id: "", key: Data(), dg: Data(), sz: size, csz: size)
        let body: ContentEnvelope.Body
        switch meta.kind {
        case "photo": body = .photo(placeholder, w: 0, h: 0, thumb: nil, replyTo: nil)
        case "voice": body = .voice(placeholder, duration: meta.duration, replyTo: nil)
        default: body = .file(placeholder, name: meta.name, mime: meta.mime, replyTo: nil)
        }
        let at = stamp()
        let envelope = ContentEnvelope(
            id: id, at: Int64((at.timeIntervalSince1970 * 1000).rounded()), body: body, ttl: currentTTL(meta.chatID),
            caption: meta.kind == "voice" ? nil : caption)
        let record = MessageRecord(
            id: id, chatID: meta.chatID, conversationID: nil, senderAccount: deps.me.accountID, senderDevice: deps.me.deviceID,
            sentAt: at, receivedAt: at, envelope: envelope.encoded(), status: "sending", replyTo: nil, editedAt: nil,
            deleted: false, undecryptable: false,
            folded: fold([meta.kind == "file" ? meta.name : "", envelope.caption ?? ""].filter { !$0.isEmpty }.joined(separator: " ")),
            expiresAt: Outbox.expiry(of: envelope), caption: envelope.caption)
        do {
            try repo.transaction {
                guard try repo.insertMessage(record) else { throw AttachmentServiceError.pendingMissing }
                // The sender sees (and can play) the picked file while it uploads.
                try repo.upsertAttachment(AttachmentRecord(
                    id: AttachmentService.placeholderAttachmentID(id), messageID: id, kind: meta.kind, key: Data(), digest: Data(),
                    size: size, cipherSize: size, state: "local", downloaded: size, localPath: staged.path))
            }
        } catch {
            PendingUpload.removeFolder(folder)
            return false
        }
        bumpActivity(meta.chatID, at: at)
        refresh()
        startUpload(id)
        return true
    }

    private enum UploadOutcome { case done, failed, permanent, cancelled, tooBig }

    /// Runs the upload of a pending attachment in the background, once per message at a time.
    private func startUpload(_ messageID: String) {
        guard let attachments, !closed, !uploading.contains(messageID), let folder = pendingFolder(messageID) else { return }
        uploading.insert(messageID)
        tooBig.remove(messageID)
        uploadFraction[messageID] = 0
        let outbox = outbox
        uploadTasks[messageID] = background { [weak self] in
            let outcome: UploadOutcome
            do {
                try await attachments.uploadPending(messageID: messageID, pendingDir: folder, progress: { fraction in
                    Task { @MainActor in self?.setUploadFraction(messageID, fraction) }
                })
                outcome = .done
            } catch is CancellationError {
                outcome = .cancelled
            } catch let error as AttachmentServiceError where error == .pendingMissing || error == .cannotPrepare {
                outcome = .permanent
            } catch let error as AttachmentServiceError where error == .tooBig {
                outcome = .tooBig
            } catch {
                outcome = .failed
            }
            if outcome == .done { try? await outbox.flush() }
            await self?.uploadFinished(messageID, outcome)
        }
    }

    private func setUploadFraction(_ messageID: String, _ fraction: Double) {
        guard uploading.contains(messageID) else { return }
        uploadFraction[messageID] = min(max(fraction, 0), 1)
        refresh()
    }

    private func uploadFinished(_ messageID: String, _ outcome: UploadOutcome) {
        uploading.remove(messageID)
        uploadFraction[messageID] = nil
        uploadTasks[messageID] = nil
        guard !closed else { return }
        switch outcome {
        case .done, .cancelled: break
        case .failed:
            _ = try? repo.markUnsentFailed(id: messageID)
        case .tooBig:
            // Stays as a failed row that says why, instead of vanishing.
            tooBig.insert(messageID)
            _ = try? repo.markUnsentFailed(id: messageID)
        case .permanent:
            // It can never be sent: the row goes, with its files.
            _ = try? repo.deleteUnsentMessage(id: messageID, sender: deps.me.accountID)
            if let folder = pendingFolder(messageID) { PendingUpload.removeFolder(folder) }
        }
        refresh()
    }

    /// The upload committed but the plaintext was not moved to the attachment's local path: put it there; if that is
    /// not possible, the attachment becomes "remote" so a download can fetch the sender's own upload.
    private func finishInterruptedMove(messageID: String, folder: URL) {
        guard let record = (try? repo.attachment(messageID: messageID)) ?? nil, record.state == "local",
              let path = record.localPath else { return }
        let fm = FileManager.default
        let local = URL(fileURLWithPath: path)
        if fm.fileExists(atPath: local.path) { return }
        func giveUp() { try? repo.setAttachmentState(id: record.id, state: "remote", downloaded: 0, localPath: nil) }
        guard MediaPathGuard.isInside(local, directory: deps.mediaDirectory), let meta = PendingUpload.read(from: folder) else { return giveUp() }
        let prepared = folder.appendingPathComponent("prepared.jpg")
        let source = meta.kind == "photo" && fm.fileExists(atPath: prepared.path) ? prepared : folder.appendingPathComponent(meta.sourceName)
        guard fm.fileExists(atPath: source.path) else { return giveUp() }
        do {
            try fm.moveItem(at: source, to: local)
            try applyProtection(to: local, .complete)
        } catch {
            try? fm.removeItem(at: local)
            giveUp()
        }
    }

    /// At start-up: an upload that was running when the app stopped is not running now, so its row is "failed" (the
    /// files are kept, "Try again" uploads again). A folder whose message is gone, deleted, or already queued is
    /// removed.
    private func recoverPendingUploads() {
        let fm = FileManager.default
        let root = PendingUpload.root(in: deps.mediaDirectory)
        guard let names = try? fm.contentsOfDirectory(atPath: root.path) else { return }
        for name in names {
            let folder = root.appendingPathComponent(name, isDirectory: true)
            guard AttachmentID.isValid(name), let record = (try? repo.message(id: name)) ?? nil, !record.deleted else {
                if AttachmentID.isValid(name) { try? repo.deleteAttachment(id: AttachmentService.placeholderAttachmentID(name)) }
                PendingUpload.removeFolder(folder)
                continue
            }
            if let att = ContentEnvelope.decode(record.envelope).body.attachment, AttachmentID.isValid(att.id) {
                // It was queued. The cut may have come between the commit and moving the plaintext into place: the
                // pending folder then holds the only copy, so it is moved before the folder goes.
                finishInterruptedMove(messageID: name, folder: folder)
                PendingUpload.removeFolder(folder)
            } else if !uploading.contains(name) {
                _ = try? repo.markUnsentFailed(id: name)
            }
        }
    }

    /// Cancel on an outgoing attachment. While the upload has not committed (the row still holds its placeholder), the
    /// message, its queue entry, its attachment row and the staged files go in one transaction and the upload task is
    /// stopped: nothing is delivered. Once the upload has committed, the message is sent (or about to be) and stays.
    public func cancelUpload(messageID: String, in chatID: String) {
        guard !closed, let folder = pendingFolder(messageID) else { return }
        let me = deps.me.accountID
        let repo = repo
        let mediaDirectory = deps.mediaDirectory
        var staged: [URL] = []
        let removed = (try? repo.transaction { () -> Bool in
            guard let record = try repo.message(id: messageID), record.chatID == chatID, record.senderAccount == me, !record.deleted,
                  record.status == "sending" || record.status == "failed" else { return false }
            let body = ContentEnvelope.decode(record.envelope).body
            // A real attachment ID means the upload committed: the message is queued and cancel is too late.
            guard let att = body.attachment, !AttachmentID.isValid(att.id) else { return false }
            if let path = try repo.attachment(messageID: messageID)?.localPath,
               MediaPathGuard.isInside(URL(fileURLWithPath: path), directory: mediaDirectory) {
                staged.append(URL(fileURLWithPath: path))
            }
            return try repo.deleteUnsentMessage(id: messageID, sender: me)
        }) ?? false
        guard removed else { return }
        if let task = uploadTasks[messageID] { tasks[task]?.cancel() }
        uploadFraction[messageID] = nil
        for url in staged { try? FileManager.default.removeItem(at: url) }
        PendingUpload.removeFolder(folder)
        refresh()
    }

    /// A reader for the chat's photo and file messages that works off the main actor: one query per page, newest
    /// first (see `AttachmentPager`).
    public func attachmentPager(for chatID: String) -> AttachmentPager {
        let repo = repo
        return { before, limit in
            let anchor = before.flatMap { (try? repo.message(id: $0)) ?? nil }
            if before != nil, anchor == nil { return [] }
            let rows = (try? repo.attachmentMessages(chatID: chatID, before: anchor, limit: limit)) ?? []
            return rows.map { record in
                ChatMapping.message(record, attachment: (try? repo.attachment(messageID: record.id)) ?? nil, reactions: [],
                                    displayName: { _ in nil })
            }
        }
    }

    /// Several photos or videos from the preview screen: each row shows in order, and the caption travels in the
    /// first item (`c`), so no text message follows it. A first item that cannot be staged takes the caption with it.
    public func sendMedia(_ items: [OutgoingMedia], caption: String, in chatID: String) {
        let text = Self.cleanCaption(caption)
        func step(_ index: Int) {
            guard index < items.count, !closed else { return }
            sendMedia(items[index].url, kind: items[index].kind, duration: 0, chatID: chatID, caption: index == 0 ? text : nil) { staged in
                if items[index].deleteAfterStaging { try? FileManager.default.removeItem(at: items[index].url) }
                step(index + 1)
            }
        }
        step(0)
    }

    static func mimeType(of url: URL, kind: MediaKind) -> String {
        switch kind {
        case .photo: return "image/jpeg"
        case .voice: return "audio/x-caf"
        case .video, .file:
            // A video gets the real video mime (quicktime for .mov, mp4 for .mp4 and .m4v), so both apps agree.
            if let mime = ChatMedia.videoMime(forExtension: url.pathExtension) { return mime }
            #if canImport(UniformTypeIdentifiers)
            if let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType { return mime }
            #endif
            return "application/octet-stream"
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
        let me = deps.me.accountID
        let now = deps.now()
        let repo = repo
        // One transaction reads the row, applies the edit and decides whether an edit control is needed, so the upload's
        // commit (which also runs in a transaction) is either wholly before it or wholly after it. Before: the commit
        // re-reads the row and carries the new caption, no control. After: the message is queued with the old caption
        // and the control follows it.
        let outcome: (queued: Bool, wireText: String)? = try? repo.transaction { () -> (queued: Bool, wireText: String)? in
            guard let record = try repo.message(id: messageID), record.chatID == chatID, record.senderAccount == me, !record.deleted else { return nil }
            // Text and caption edits follow one rule, shared with the receiver (`ContentEnvelope.edited`).
            guard let result = ContentEnvelope.decode(record.envelope).edited(text: newText, fold: fold) else { return nil }
            guard try repo.applyEdit(id: messageID, sender: me, envelope: result.envelope.encoded(), folded: result.folded, at: now,
                                     caption: result.envelope.caption, linkPreview: result.envelope.preview?.json) else { return nil }
            let queued = result.envelope.body.attachment.map { AttachmentID.isValid($0.id) } ?? true
            return (queued, result.wireText)
        } ?? nil
        guard let outcome else { return }
        refresh()
        if outcome.queued { sendControl(.edit(target: messageID, text: outcome.wireText), chatID: chatID) }
    }

    public func delete(messageID: String, in chatID: String) {
        guard let record = (try? repo.message(id: messageID)) ?? nil, record.chatID == chatID,
              record.senderAccount == deps.me.accountID, !record.deleted,
              !record.id.hasPrefix(ChatMapping.callPrefix) else { return }
        // One transaction reads whether the message was ever queued and marks it deleted, so an upload that commits
        // in between cannot leave the peer with a message that has no Delete after it. (The upload's own commit also
        // refuses a row already marked deleted.)
        let accountID = deps.me.accountID
        let marked: Bool? = try? repo.transaction { () -> Bool? in
            guard let current = try repo.message(id: messageID), current.chatID == chatID, current.senderAccount == accountID,
                  !current.deleted else { return nil }
            let never = ContentEnvelope.decode(current.envelope).body.attachment.map { !AttachmentID.isValid($0.id) } ?? false
            guard try repo.markDeleted(id: messageID, sender: accountID) else { return nil }
            return never
        }
        guard let neverQueued = marked else { return }
        if neverQueued, !uploading.contains(messageID) {
            // A running upload cleans up after itself when it finds the message deleted.
            _ = try? repo.deleteUnsentMessage(id: messageID, sender: deps.me.accountID)
            if let folder = pendingFolder(messageID) { PendingUpload.removeFolder(folder) }
        }
        refresh()
        if !neverQueued { sendControl(.delete(target: messageID), chatID: chatID) }
    }

    /// Queued in the chat's outbox, behind the message it refers to; retried like a message.
    /// The life a new message gets: the chat's timer, or none while it is off.
    private func currentTTL(_ chatID: String) -> Int? {
        let seconds = (try? repo.timerSeconds(chatID: chatID)) ?? 0
        return seconds > 0 ? seconds : nil
    }

    // MARK: - Disappearing messages and profiles

    public func timerSeconds(_ chatID: String) -> Int {
        (try? repo.timerSeconds(chatID: chatID)) ?? 0
    }

    /// Sets the timer here at once, writes the system line, and queues one `timer` message behind what is already
    /// queued in the chat, so the next message carries the new `ttl` on both sides. Refuses a number that is not a
    /// choice, and does nothing when the timer already has that value.
    public func setTimer(_ chatID: String, seconds: Int) {
        guard !closed, TimerChoice.from(seconds: seconds) != nil, ensureChatRow(chatID) != nil,
              timerSeconds(chatID) != seconds else { return }
        let at = deps.now()
        let line = MessageRecord(
            id: "sys-timer-\(freshID())", chatID: chatID, conversationID: nil, senderAccount: ChatMapping.systemSender,
            senderDevice: nil, sentAt: at, receivedAt: at,
            envelope: (try? JSONEncoder().encode(SystemEvent.timer(seconds: seconds, name: nil))) ?? Data(), status: "sent",
            replyTo: nil, editedAt: nil, deleted: false, undecryptable: false, folded: "")
        let repo = repo
        // The envelope ID is chosen here so the same ID is stored and sent: it breaks a tie at the same millisecond.
        let envelopeID = UUID().uuidString.lowercased()
        guard (try? repo.transaction { () throws -> Void in
            try repo.setTimer(chatID: chatID, seconds: seconds)
            try repo.set(IncomingHandler.timerIDKey(chatID), Data(envelopeID.utf8))
            try repo.insertMessage(line)
            try repo.set(IncomingHandler.timerAtKey(chatID), Data(String(Int64((at.timeIntervalSince1970 * 1000).rounded())).utf8))
        }) != nil else { return }
        bumpActivity(chatID, at: at)
        refresh()
        sendControl(.timer(seconds: seconds), chatID: chatID, id: envelopeID)
    }

    /// Removes what has expired and returns the IDs (so delivered notifications can go too). Files are deleted
    /// after the database commit, only inside the media folder; an unfinished upload's folder goes with its row.
    @discardableResult
    public func sweepExpired(now: Date) -> [String] {
        guard !closed, let removed = try? repo.sweepExpired(now: now, me: deps.me.accountID), !removed.isEmpty else { return [] }
        for message in removed {
            for path in message.localPaths where MediaPathGuard.isInside(URL(fileURLWithPath: path), directory: deps.mediaDirectory) {
                try? FileManager.default.removeItem(atPath: path)
            }
            if let folder = pendingFolder(message.id) { PendingUpload.removeFolder(folder) }
            uploadFraction[message.id] = nil
            tooBig.remove(message.id)
        }
        refresh()
        return removed.map(\.id)
    }

    public func nextExpiry() -> Date? { (try? repo.nextExpiry()) ?? nil }

    public func profile(for accountID: String) -> StoredProfile? {
        guard let record = (try? repo.profile(for: accountID)) ?? nil else { return nil }
        return StoredProfile(accountID: record.accountID, name: record.name, photo: record.photo, ver: record.ver)
    }

    /// The same rules as a received `profile`: a name that passes `DisplayNameRule.clean` unchanged, a JPEG photo of
    /// at most 24,576 bytes, and a version strictly higher than the stored one.
    @discardableResult
    public func saveProfile(accountID: String, name: String, photo: Data?, ver: Int) -> Bool {
        guard !closed, DisplayNameRule.clean(name) == name,
              photo.map(ProfilePhotoRule.isAcceptable) ?? true,
              ProfileMerge.accept(current: (try? repo.profile(for: accountID))?.ver, incoming: ver),
              (try? repo.saveProfile(accountID: accountID, name: name, photo: photo, ver: ver)) == true else { return false }
        refresh()
        return true
    }

    /// Makes a group chat. The outbox then sends what the create queued (the `group` envelope, the own `profile`).
    public func createGroup(accounts: [String], name: String, photo: Data?) async -> GroupCreateOutcome {
        guard !closed else { return .failed(.other) }
        defer { refresh() }
        do {
            let made = try await conversations.createGroup(accounts: accounts, name: name, photo: photo)
            let outbox = outbox
            background { try? await outbox.flush() }
            return .created(chatID: made.chatID, notAdded: made.notAdded)
        } catch {
            return .failed(GroupCreateFailure(error: error))
        }
    }

    func sendControl(_ body: ContentEnvelope.Body, chatID: String, id: String? = nil) {
        guard (try? outbox.enqueueControl(body, chatID: chatID, id: id)) != nil else { return }
        flushOutboxSoon()
    }

    func flushOutboxSoon() {
        let outbox = outbox
        background { try? await outbox.flush() }
    }

    // MARK: - Calls

    public func recordCall(_ summary: CallSummary, contactID: String, outgoing: Bool, at date: Date) {
        guard !ChatID.isGroup(contactID), let chatID = ensureChatRow(contactID),
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
        guard supportsSafetyNumbers, let row = coveredSafety(chatID) else { return nil }
        return SafetyNumber(groups: ChatMapping.groups(row.current), isVerified: row.verified == row.current)
    }

    /// The members of one of the chat's groups could not be read. A database error counts as unreadable.
    public func membersUnreadable(for chatID: String) -> Bool {
        (try? Membership.membersUnreadable(chatID: chatID, db: repo)) ?? true
    }

    /// Local only: nothing is sent.
    public func setVerified(_ verified: Bool, chatID: String) {
        guard supportsSafetyNumbers, let row = coveredSafety(chatID) else { return }
        try? repo.setVerified(chatID: chatID, verified ? row.current : nil)
        refresh()
    }

    /// The stored number, only when it is the number the preferred conversation's stored members give now, with no
    /// placeholder key among them and no group of the chat flagged as unreadable. Anything else (placeholder rows
    /// from before the real keys, unreadable members, a number written for another conversation, members that
    /// cannot be framed) is hidden and cannot be verified, and the conversation is re-baselined from the engine.
    private func coveredSafety(_ chatID: String) -> (current: String, verified: String?)? {
        guard let row = (try? repo.safety(chatID: chatID)) ?? nil,
              let conversation = (try? repo.preferredConversation(chatID: chatID)) ?? nil else { return nil }
        // Members that could not be read: no number until a read works (each flagged conversation is read again).
        if membersUnreadable(for: chatID) {
            for flagged in (try? repo.conversations(chatID: chatID)) ?? []
            where flagged.active && ((try? Membership.isUnreadable(conversationID: flagged.serverID, db: repo)) ?? true) {
                rebaseline(flagged)
            }
            return nil
        }
        let keys = ((try? repo.members(conversationID: conversation.serverID)) ?? [])
            .map { MemberKey(identity: $0.identity, signatureKey: $0.signatureKey) }
        let number = try? SafetyNumberCalc.compute(accountA: deps.me.accountID, accountB: chatID, members: keys).joined()
        guard number == row.current, !keys.contains(where: { Membership.isPlaceholder($0.identity, $0.signatureKey) }) else {
            rebaseline(conversation)
            return nil
        }
        return row
    }

    /// Reads the conversation's keys from the engine again (`Membership.update`), once per conversation per launch;
    /// a failure leaves the number hidden (retrying on every refresh would loop). Placeholder rows are re-baselined
    /// silently (ruling in plans/README.md); any other change is flagged.
    private func rebaseline(_ conversation: ConversationRecord) {
        guard rebaselined.insert(conversation.serverID).inserted else { return }
        let deps = deps
        background {
            _ = try? await deps.lock.withAsyncLock(timeout: InboxProcessor.lockTimeout) {
                try await Membership(deps: deps).update(conversation: conversation, db: deps.repo)
            }
        }
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
        guard !ChatID.isGroup(contactID) else { return }   // a group is not a person
        do {
            try repo.transaction {
                try repo.block(contactID)
                try repo.deleteChat(id: contactID)
                if var row = try repo.contact(id: contactID) {
                    row.isContact = false
                    try repo.upsertContact(row)
                }
                try repo.set(PendingActions.blockPrefix + contactID, Data([1]))
                try repo.set(PendingActions.unblockPrefix + contactID, Data())   // a later block cancels an unsent unblock
            }
        } catch { return }
        refresh()
        kickPending()
    }

    public func unblock(contactID: String) {
        guard !ChatID.isGroup(contactID) else { return }
        do {
            try repo.transaction {
                try repo.unblock(contactID)
                // An unsent block is dropped; the server is told to lift any block it has (idempotent).
                try repo.set(PendingActions.blockPrefix + contactID, Data())
                try repo.set(PendingActions.unblockPrefix + contactID, Data([1]))
            }
        } catch { return }
        refresh()
        kickPending()
    }

    public func report(chatID: String) {
        guard !ChatID.isGroup(chatID) else { return }   // a report names a person, not a group
        let records = (try? repo.messages(chatID: chatID, before: nil, limit: 40)) ?? []
        let lines = map(records).filter { message in
            if case .system = message.content { return false }
            return true
        }.suffix(Self.reportedMessageCount).map { ReportedMessage(sentAt: $0.sentAt, text: Self.storedText($0)) }
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
        guard !ChatID.isGroup(contactID) else { return contactID }
        let id = ensureChatRow(contactID) ?? contactID
        refresh()
        return id
    }

    /// Makes sure the contact row and the chat row exist. `nil` when the database write failed.
    private func ensureChatRow(_ contactID: String) -> String? {
        do {
            if try repo.chat(id: contactID) != nil { return contactID }
            // A group chat is made only from its conversation; a `g:` ID never becomes a contact's chat.
            guard !ChatID.isGroup(contactID) else { return nil }
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

    /// One statement that raises `last_activity` only: pin, mute and archive are never written back from an older read.
    private func bumpActivity(_ chatID: String, at date: Date) {
        try? repo.bumpActivity(chatID: chatID, at: date)
    }

    // MARK: - Chat list actions (this device only)

    @discardableResult
    public func setPinned(_ chatID: String, _ pinned: Bool) -> Bool {
        let done: Bool
        if pinned {
            done = (try? repo.pin(chatID: chatID, at: deps.now(), limit: ChatListOrder.maxPins)) ?? false
        } else {
            done = (try? repo.setPinnedAt(chatID: chatID, nil)) != nil
        }
        refresh()
        return done
    }

    public func setMuted(_ chatID: String, _ muted: Bool) {
        try? repo.setMuted(chatID: chatID, muted)
        refresh()
    }

    public func setArchived(_ chatID: String, _ archived: Bool) {
        try? repo.setArchived(chatID: chatID, archived)
        refresh()
    }

    /// Called on every keystroke: the text is kept and shown at once (the cached chat is patched), nobody is told,
    /// and the database write waits for a pause in typing (`draftWriteDelay`). `flushDrafts` writes it early: when the
    /// chat is left, when the app stops being active, and a send writes it itself. A chat with no row yet keeps its
    /// draft in memory; `reload` writes it when the row appears.
    public func setDraft(_ chatID: String, _ text: String) {
        let blank = text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        draftsPending[chatID] = blank ? "" : text
        if let i = allChats.firstIndex(where: { $0.id == chatID }) { allChats[i].draft = blank ? nil : text }
        draftTask?.cancel()
        let delay = draftWriteDelay
        draftTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            self?.writePendingDrafts()
        }
    }

    public func draft(for chatID: String) -> String {
        draftsPending[chatID] ?? allChats.first { $0.id == chatID }?.draft ?? ""
    }

    public func flushDrafts() {
        draftTask?.cancel()
        draftTask = nil
        if writePendingDrafts() { onChange?() }
    }

    /// Writes every pending draft whose chat has a row. True when something was written.
    @discardableResult
    private func writePendingDrafts() -> Bool {
        let before = draftsPending.count
        for id in Array(draftsPending.keys) { persistDraft(id) }
        return draftsPending.count < before
    }

    private func setDraftQuietly(_ chatID: String) {
        draftsPending[chatID] = ""
        persistDraft(chatID)
    }

    private func persistDraft(_ chatID: String) {
        guard let text = draftsPending[chatID] else { return }
        if (try? repo.setDraft(chatID: chatID, text, at: deps.now())) == true { draftsPending[chatID] = nil }
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
