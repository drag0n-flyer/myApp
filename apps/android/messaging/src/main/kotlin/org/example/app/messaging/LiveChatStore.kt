package org.example.app.messaging

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.app.contracts.CallSignalSealer
import org.example.app.contracts.DeliveryApi
import org.example.app.contracts.MlsEngine
import org.example.app.core.chats.CallSummary
import org.example.app.core.chats.Chat
import org.example.app.core.chats.ChatFolder
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.Contact
import org.example.app.core.chats.IncomingCallDecision
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.MessageHit
import org.example.app.core.chats.MessagePreview
import org.example.app.core.chats.SafetyNumber
import org.example.app.core.settings.MediaKind
import org.example.app.messaging.content.Att
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.content.TextLimit
import org.example.app.messaging.media.Downloader
import org.example.app.messaging.media.OpenedAttachment
import org.example.app.messaging.media.Uploader
import org.example.app.messaging.ports.AttachmentState
import org.example.app.messaging.ports.CallRecord
import org.example.app.messaging.ports.ChatRecord
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.ports.FolderRecord
import org.example.app.messaging.ports.MessageRecord
import org.example.app.messaging.ports.MlsLock
import org.example.app.messaging.ports.Repository
import org.example.app.messaging.send.Outbox
import org.example.app.messaging.send.Receipts
import org.example.app.messaging.sync.ConversationManager
import org.example.app.messaging.sync.EscrowSealer
import org.example.app.messaging.sync.InboxProcessor
import org.example.app.messaging.sync.LocalDevice
import org.example.app.messaging.sync.Membership

/** A user found on the server. `:app` adapts `SocialApi.lookUpUser`. */
class FoundUser(val accountId: String, val username: String, val displayName: String)

class ReportedLine(val sentAt: Long, val text: String)

/** The server calls the store needs beyond delivery. `:messaging` cannot depend on `:net`; `:app` adapts `SocialApi`. */
interface SocialPort {
    /** Null when there is no such user. */
    suspend fun lookUpUser(username: String): FoundUser?
    suspend fun block(accountId: String)
    suspend fun report(accountId: String, conversationId: String?, messages: List<ReportedLine>)
}

/** A photo, voice note or file ready to upload. `:app` makes it (`PhotoPrep`, `VoiceRecorder`). */
class PreparedMedia(
    val file: File, val width: Int = 0, val height: Int = 0, val thumbnail: ByteArray? = null,
    val duration: Double = 0.0, val name: String = file.name,
)

fun interface MediaPreparer {
    /** Null when the file cannot be sent. */
    suspend fun prepare(path: String, kind: MediaKind): PreparedMedia?
}

/** Where attachments live. [blobDir] holds encrypted downloads, [openDir] decrypted files in view, [tempDir] outgoing ciphertext. */
class MediaDirs(val blobDir: File, val openDir: File, val tempDir: File)

/**
 * The real [ChatStore]: StateFlow caches of the chats and requests, reloaded from the database after every write, so
 * synchronous reads stay synchronous. Writes run in order on one background worker; sending runs in a second loop
 * that never holds up the writes. A failed write or network call leaves the store as it was; nothing here throws to
 * the UI. Never logs text, names or keys.
 *
 * One read-receipts switch ([readReceiptsEnabled]) is passed to both the [Outbox] and the [Receipts] this store
 * builds, and drives how message status is shown ([Receipts.displayStatus]).
 *
 * Start-up (task I1): [resumePendingCreates], [sweepStartup], then an inbox sync.
 *
 * @param maxAttachmentBytes the server's `limits.attachmentBytes` (server info); the [Downloader] refuses more.
 * @param fold search folding; the app passes `:store`'s `TextFold::fold`.
 * @param mediaSweep `MediaCache.sweep(context)` from `:app`, run by [sweepStartup] with the downloader's sweep.
 */
class LiveChatStore(
    private val device: LocalDevice,
    mls: MlsEngine,
    private val server: DeliveryApi,
    private val repo: Repository,
    lock: MlsLock,
    private val clock: Clock,
    private val conversations: ConversationManager,
    private val inbox: InboxProcessor,
    val membership: Membership,
    private val sealer: ChatCallSealer,
    escrow: EscrowSealer?,
    private val readReceiptsEnabled: () -> Boolean,
    val maxAttachmentBytes: Long,
    dirs: MediaDirs,
    private val social: SocialPort,
    private val scope: CoroutineScope,
    private val fold: (String) -> String = { it.lowercase(Locale.ROOT) },
    private val mediaPreparer: MediaPreparer = MediaPreparer { path, kind -> if (kind == MediaKind.File) PreparedMedia(File(path)) else null },
    private val mediaSweep: () -> Unit = {},
    onEscrowRejected: () -> Unit = {},
) : ChatStore, CallSignalSealer by sealer {

    val outbox = Outbox(
        device, mls, server, repo, lock, clock, conversations, escrow, syncOnce = { inbox.sync() },
        readReceiptsEnabled = readReceiptsEnabled, fold = fold, onEscrowRejected = onEscrowRejected,
    )
    val receipts = Receipts(outbox, readReceiptsEnabled)
    val downloader = Downloader(server, dirs.blobDir, dirs.openDir, maxAttachmentBytes)
    private val uploader = Uploader(server, dirs.tempDir)

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    private val _folders = MutableStateFlow(listOf(ChatFolder.All, ChatFolder.Unread))
    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    private val _requests = MutableStateFlow<List<Chat>>(emptyList())
    private val _blocked = MutableStateFlow<Set<String>>(emptySet())
    private val _reported = MutableStateFlow<Set<String>>(emptySet())
    override val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()
    override val folders: StateFlow<List<ChatFolder>> = _folders.asStateFlow()
    override val chats: StateFlow<List<Chat>> = _chats.asStateFlow()
    override val requests: StateFlow<List<Chat>> = _requests.asStateFlow()
    override val blockedIds: StateFlow<Set<String>> = _blocked.asStateFlow()
    override val reportedChatIds: StateFlow<Set<String>> = _reported.asStateFlow()

    @Volatile private var meContact = Contact(device.accountId, "You", "")
    @Volatile private var contactRows: Map<String, ContactRecord> = emptyMap()
    override val me: Contact get() = meContact

    private val reloadLock = Any()
    private val writes = Channel<() -> Unit>(Channel.UNLIMITED)
    private val inFlight = MutableStateFlow(0)
    private val flushRunning = AtomicBoolean(false)
    private val flushWanted = AtomicBoolean(false)
    private val pendingRunning = AtomicBoolean(false)
    private val pendingAgain = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    init {
        receipts.attach(inbox)
        reload()
        scope.launch {
            for (job in writes) {
                try { job() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                reload()
                inFlight.update { it - 1 }
            }
        }
    }

    // Cache

    /** Reloads the caches from the database. Call it after the inbox changed (a push, a sync). */
    fun refresh() = reload()

    /** Waits until every write, send and pending request started so far is done. For tests and shutdown. */
    suspend fun settle() {
        inFlight.first { it == 0 }
    }

    /** Stops the store before its data is wiped (sign-out): later work does nothing. */
    suspend fun shutdown() {
        closed.set(true)
        settle()
    }

    private fun write(job: () -> Unit) {
        if (closed.get()) return
        inFlight.update { it + 1 }
        if (writes.trySend(job).isFailure) inFlight.update { it - 1 }
    }

    private fun requestFlush() {
        if (closed.get()) return
        flushWanted.set(true)
        if (!flushRunning.compareAndSet(false, true)) return
        inFlight.update { it + 1 }
        scope.launch {
            try {
                while (flushWanted.getAndSet(false)) {
                    try { outbox.flush() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    runPending()
                    reload()
                }
            } finally {
                flushRunning.set(false)
                inFlight.update { it - 1 }
            }
            // A request that came in after the last check but before the flag was cleared.
            if (flushWanted.get()) requestFlush()
        }
    }

    private fun nowMs() = clock.now()
    private fun freshId() = UUID.randomUUID().toString().lowercase()
    private val showRead get() = readReceiptsEnabled()

    private fun reload() = synchronized(reloadLock) {
        val rows = runCatching { repo.contacts() }.getOrDefault(emptyList())
        contactRows = rows.associateBy { it.accountId }
        contactRows[device.accountId]?.let { meContact = Contact(it.accountId, it.displayName.ifEmpty { "You" }, it.username) }
        _contacts.value = rows.filter { it.isContact }.sortedWith(compareBy({ it.displayName }, { it.accountId })).map(::contactOf)

        val folderRecords = runCatching { repo.folders() }.getOrDefault(emptyList())
        _folders.value = listOf(ChatFolder.All, ChatFolder.Unread) + folderRecords.map { ChatFolder(it.id, it.name, ChatFolder.Kind.Custom) }
        val folderIds = HashMap<String, MutableSet<String>>()
        for (f in folderRecords) for (chatId in runCatching { repo.folderChats(f.id) }.getOrDefault(emptyList())) {
            folderIds.getOrPut(chatId) { mutableSetOf() } += f.id
        }

        val records = runCatching { repo.chats() }.getOrDefault(emptyList()).sortedByDescending { it.lastActivity }
        val built = records.mapNotNull { record ->
            val all = repo.conversations(record.id)
            val latest = repo.messages(record.id, null, CACHED_MESSAGES)
            // A request made only by a failed Welcome rests on the server's unproven claim of who sent it: it is not
            // shown as a request (there is no conversation to verify it against).
            if (record.isRequest && all.isEmpty() && latest.isNotEmpty() && latest.all { it.id.startsWith(ChatMapping.JOIN_FAILED_PREFIX) }) {
                return@mapNotNull null
            }
            // The preferred conversation is the active one. An inactive conversation (a create in progress, or one I
            // was removed from) says nothing about the chat itself and is never turned into a "removed" state here.
            val preferred = repo.preferredConversation(record.id) ?: all.firstOrNull()
            ChatMapping.chat(record, all, preferred, folderIds[record.id].orEmpty(), map(latest))
        }
        _chats.value = built.filter { !it.isRequest }
        _requests.value = built.filter { it.isRequest }
        _blocked.value = runCatching { repo.blockedIds().toSet() }.getOrDefault(emptySet())
        _reported.value = runCatching { repo.entries(REPORTED_PREFIX).filterValues { it.isNotEmpty() }.keys.map { it.removePrefix(REPORTED_PREFIX) }.toSet() }
            .getOrDefault(emptySet())
    }

    private fun map(records: List<MessageRecord>): List<Message> {
        val showSafety = supportsSafetyNumbers
        val show = showRead
        return records.map { record ->
            val plain = record.senderAccount != ChatMapping.SYSTEM_SENDER && !record.id.startsWith(ChatMapping.CALL_PREFIX)
            ChatMapping.message(
                record,
                attachment = if (plain) repo.attachmentForMessage(record.id) else null,
                reactions = if (plain && !record.deleted) repo.reactions(record.id) else emptyList(),
                showRead = show,
                displayName = { account -> nameOf(contactRows[account]) },
            )
        }.filter { showSafety || it.content != MessageContent.System(org.example.app.core.chats.SystemEvent.SafetyNumberChanged) }
    }

    private fun nameOf(row: ContactRecord?): String? {
        if (row == null) return null
        if (row.displayName.isNotEmpty()) return row.displayName
        return row.username.ifEmpty { null }
    }

    private fun contactOf(row: ContactRecord) = Contact(row.accountId, nameOf(row) ?: "Unknown", row.username)

    // Reads

    override fun chat(id: String): Chat? = _chats.value.firstOrNull { it.id == id } ?: _requests.value.firstOrNull { it.id == id }

    override fun contact(id: String): Contact? = if (id == device.accountId) meContact else contactRows[id]?.let(::contactOf)

    /** Reads the database: call it off the main thread. */
    override fun messages(chatId: String, before: String?, limit: Int): List<Message> {
        if (limit <= 0) return emptyList()
        var anchor: MessageRecord? = null
        if (before != null) {
            anchor = repo.message(before)?.takeIf { it.chatId == chatId } ?: return emptyList()
        }
        return map(repo.messages(chatId, anchor, limit))
    }

    override fun searchMessages(query: String, limit: Int): List<MessageHit> {
        val folded = fold(query.trim())
        if (folded.isEmpty() || limit <= 0) return emptyList()
        val requestIds = _requests.value.map { it.id }.toSet()
        return repo.search(folded, limit).mapNotNull { hit ->
            if (hit.chatId in requestIds) return@mapNotNull null
            val record = repo.message(hit.messageId) ?: return@mapNotNull null
            val text = when (val p = map(listOf(record)).first().preview) {
                is MessagePreview.Text -> p.text
                is MessagePreview.Resource -> ""
            }
            MessageHit(hit.chatId, hit.messageId, text)
        }
    }

    // Sending

    override fun send(content: MessageContent, chatId: String, replyTo: String?) {
        val raw = (content as? MessageContent.Text)?.text ?: return
        val text = TextLimit.truncate(raw)
        if (text.isBlank()) return
        write {
            if (!ensureChatRow(chatId)) return@write
            outbox.enqueue(ContentEnvelope.Text(freshId(), nowMs(), text, replyTo), chatId)
            bumpActivity(chatId, nowMs())
            requestFlush()
        }
    }

    /** Messages that gave up sending. `:core` has no failed status, so they show as sending; the UI offers [retrySend]. */
    fun failedMessageIds(chatId: String): Set<String> =
        repo.messages(chatId, null, 200).filter { it.status == "failed" && it.senderAccount == device.accountId }.map { it.id }.toSet()

    fun retrySend(messageId: String) = write { if (outbox.retry(messageId)) requestFlush() }

    /** [path] is the picked or recorded file; [MediaPreparer] turns it into the upload. */
    override fun sendAttachment(path: String, kind: MediaKind, chatId: String) {
        if (closed.get()) return
        inFlight.update { it + 1 }
        scope.launch {
            try {
                val prepared = mediaPreparer.prepare(path, kind) ?: return@launch
                val att = uploader.upload(prepared.file)
                val id = freshId()
                val at = nowMs()
                val envelope = when (kind) {
                    MediaKind.Photo, MediaKind.Video -> ContentEnvelope.Photo(id, at, att, prepared.width, prepared.height, prepared.thumbnail)
                    MediaKind.Voice -> ContentEnvelope.Voice(id, at, att, prepared.duration)
                    MediaKind.File -> ContentEnvelope.File(id, at, att, prepared.name, "application/octet-stream")
                }
                if (!ensureChatRow(chatId)) return@launch
                outbox.enqueue(envelope, chatId)
                bumpActivity(chatId, at)
                reload()
                requestFlush()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                reload()
                inFlight.update { it - 1 }
            }
        }
    }

    override fun download(messageId: String, chatId: String) {
        val record = repo.attachmentForMessage(messageId) ?: return
        val att = Att(record.id, record.key, record.digest, record.size, record.cipherSize)
        if (closed.get()) return
        inFlight.update { it + 1 }
        scope.launch {
            try {
                repo.setAttachmentState(record.id, AttachmentState.DOWNLOADING, 0, null)
                reload()
                try {
                    val blob = downloader.download(att)
                    repo.setAttachmentState(record.id, AttachmentState.LOCAL, record.cipherSize, blob.path)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    repo.setAttachmentState(record.id, AttachmentState.FAILED, 0, null)
                }
            } finally {
                reload()
                inFlight.update { it - 1 }
            }
        }
    }

    /** Decrypts a downloaded attachment for viewing. Close the result when it is no longer shown. Null when not available. */
    suspend fun openAttachment(messageId: String): OpenedAttachment? {
        val r = repo.attachmentForMessage(messageId) ?: return null
        return try { downloader.open(Att(r.id, r.key, r.digest, r.size, r.cipherSize)) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    // Start-up (task I1)

    /** Finishes every conversation create that was cut short. Call it at start-up after the first inbox sync. */
    suspend fun resumePendingCreates() {
        conversations.resumePendingCreates()
        reload()
    }

    /** Removes decrypted files and partial downloads a killed process left: `Downloader.sweepOpen`, then `MediaCache.sweep`. */
    fun sweepStartup() {
        downloader.sweepOpen()
        mediaSweep()
    }

    /** Fetches and processes the inbox, then retries pending block and report requests. */
    suspend fun syncInbox() {
        if (closed.get()) return
        try { inbox.sync() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        runPending()
        reload()
    }

    // Reactions, edits, deletes

    override fun react(emoji: String, messageId: String, chatId: String) = write {
        val record = repo.message(messageId) ?: return@write
        if (record.chatId != chatId || record.deleted || record.senderAccount == ChatMapping.SYSTEM_SENDER || record.id.startsWith(ChatMapping.CALL_PREFIX)) return@write
        val mine = repo.reactions(messageId).firstOrNull { it.accountId == device.accountId }?.emoji
        // The same emoji again takes the reaction back.
        val chosen = if (mine == emoji) "" else emoji
        repo.setReaction(messageId, device.accountId, chosen)
        sendControl(chatId) { id, at -> ContentEnvelope.React(id, at, messageId, chosen) }
    }

    override fun edit(messageId: String, chatId: String, text: String) = write {
        val newText = TextLimit.truncate(text)
        if (newText.isBlank()) return@write
        val record = repo.message(messageId) ?: return@write
        if (record.chatId != chatId || record.senderAccount != device.accountId || record.deleted) return@write
        val old = ContentCodec.decode(record.envelope) as? ContentEnvelope.Text ?: return@write
        val edited = ContentCodec.encode(ContentEnvelope.Text(old.id, old.at, newText, old.r))
        if (!repo.applyEdit(messageId, device.accountId, edited, fold(newText), nowMs())) return@write
        sendControl(chatId) { id, at -> ContentEnvelope.Edit(id, at, messageId, newText) }
    }

    override fun delete(messageId: String, chatId: String) = write {
        val record = repo.message(messageId) ?: return@write
        if (record.chatId != chatId || record.senderAccount != device.accountId || record.deleted || record.id.startsWith(ChatMapping.CALL_PREFIX)) return@write
        if (!repo.markDeleted(messageId, device.accountId)) return@write
        sendControl(chatId) { id, at -> ContentEnvelope.Delete(id, at, messageId) }
    }

    private fun sendControl(chatId: String, make: (String, Long) -> ContentEnvelope) {
        outbox.enqueueControl(chatId, make)
        requestFlush()
    }

    // Calls

    override fun recordCall(summary: CallSummary, contactId: String, outgoing: Boolean, at: Instant) = write {
        writeCallRow(
            id = ChatMapping.CALL_PREFIX + digestId("$contactId|$outgoing|${at.toEpochMilli()}|${ChatMapping.callEnvelope(summary).decodeToString()}"),
            summary = summary, contactId = contactId, outgoing = outgoing, at = at.toEpochMilli(),
        )
    }

    /**
     * Records a missed call from someone who is not a contact (the push handler's `SilentMissed`). Written once per
     * [callId]. A blocked caller leaves nothing.
     */
    suspend fun recordStrangerMissedCall(callId: String, fromAccountId: String, video: Boolean = false) {
        if (repo.isBlocked(fromAccountId)) return
        if (repo.contact(fromAccountId) == null) {
            repo.upsertContact(ContactRecord(fromAccountId, "", "", false, nowMs()))
        }
        writeCallRow(
            ChatMapping.CALL_PREFIX + digestId("stranger|$callId"), if (video) CallSummary.MissedVideo else CallSummary.MissedAudio,
            fromAccountId, outgoing = false, at = nowMs(),
        )
        reload()
    }

    /** One call row and one chat line, in one transaction. A row with this ID already there means nothing is written. */
    private fun writeCallRow(id: String, summary: CallSummary, contactId: String, outgoing: Boolean, at: Long) {
        if (!ensureChatRow(contactId)) return
        val (video, outcome, duration) = when (summary) {
            CallSummary.MissedAudio -> Triple(false, "missed", 0.0)
            CallSummary.MissedVideo -> Triple(true, "missed", 0.0)
            is CallSummary.Audio -> Triple(false, "completed", summary.duration)
            is CallSummary.Video -> Triple(true, "completed", summary.duration)
        }
        repo.transaction {
            val inserted = repo.insertMessage(
                MessageRecord(
                    id = id, chatId = contactId, conversationId = null, senderAccount = if (outgoing) device.accountId else contactId,
                    senderDevice = null, sentAt = at, receivedAt = nowMs(), envelope = ChatMapping.callEnvelope(summary),
                    status = if (outgoing) "sent" else "delivered",
                ),
            )
            if (inserted) repo.insertCall(CallRecord(id, contactId, video, outgoing, outcome, at, duration))
        }
        bumpActivity(contactId, at)
    }

    private fun digestId(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).take(12).joinToString("") { "%02x".format(it) }

    override fun incomingCall(fromAccountId: String, video: Boolean): IncomingCallDecision {
        if (repo.isBlocked(fromAccountId)) return IncomingCallDecision.SilentMissed
        if (contactRows[fromAccountId]?.isContact == true || repo.contact(fromAccountId)?.isContact == true) return IncomingCallDecision.Ring
        if (repo.contact(fromAccountId) == null) repo.upsertContact(ContactRecord(fromAccountId, "", "", false, nowMs()))
        recordCall(if (video) CallSummary.MissedVideo else CallSummary.MissedAudio, fromAccountId, false, Instant.ofEpochMilli(nowMs()))
        return IncomingCallDecision.SilentMissed
    }

    // Safety number

    /** Tied to the engine: shown only when the member keys are real (otherwise the number would cover no key). */
    override val supportsSafetyNumbers: Boolean get() = membership.realMemberKeys

    override fun safetyNumber(chatId: String): SafetyNumber? {
        if (!supportsSafetyNumbers) return null
        val row = repo.safety(chatId) ?: return null
        return SafetyNumber(ChatMapping.groups(row.current), row.verified == row.current)
    }

    /** Local only: nothing is sent. */
    override fun setVerified(verified: Boolean, chatId: String) {
        if (!supportsSafetyNumbers) return
        val row = repo.safety(chatId) ?: return
        repo.setVerified(chatId, if (verified) row.current else null)
        reload()
    }

    // Requests, contacts, blocking, reports

    /** Only the sample store simulates incoming messages. Real ones arrive through the inbox. */
    override fun receive(content: MessageContent, from: Contact) = Unit

    override fun accept(requestId: String) = write {
        val chat = repo.chat(requestId) ?: return@write
        if (!chat.isRequest) return@write
        repo.transaction {
            repo.setRequest(requestId, false)
            val row = repo.contact(requestId) ?: ContactRecord(requestId, "", "", true, nowMs())
            repo.upsertContact(row.copy(isContact = true))
        }
        val read = peerMessageIds(requestId) { it.status == "read" }
        if (read.isNotEmpty()) { receipts.read(requestId, read); requestFlush() }
    }

    override fun block(contactId: String) = write {
        repo.transaction {
            repo.block(contactId, nowMs())
            repo.deleteChat(contactId)
            repo.contact(contactId)?.let { repo.upsertContact(it.copy(isContact = false)) }
            repo.set(BLOCK_PREFIX + contactId, byteArrayOf(1))
        }
        kickPending()
    }

    override fun report(chatId: String) = write {
        val lines = repo.messages(chatId, null, 40).let { map(it) }
            .filter { it.content !is MessageContent.System }
            .takeLast(REPORTED_MESSAGES)
            .map { ReportedLine(it.sentAt.toEpochMilli(), previewText(it)) }
        val pending = PendingReport(chatId, repo.preferredConversation(chatId)?.serverId, lines.map { PendingLine(it.sentAt, it.text) })
        repo.set(PENDING_REPORT_PREFIX + freshId(), Json.encodeToString(PendingReport.serializer(), pending).encodeToByteArray())
        repo.set(REPORTED_PREFIX + chatId, byteArrayOf(1))
        kickPending()
    }

    private fun previewText(m: Message): String = when (val c = m.content) {
        is MessageContent.Text -> c.text
        is MessageContent.File -> c.name
        else -> ""
    }

    override fun markRead(chatId: String) = write {
        val chat = repo.chat(chatId) ?: return@write
        val ids = peerMessageIds(chatId) { it.status != "read" }
        if (chat.unread == 0 && ids.isEmpty()) return@write
        repo.setUnread(chatId, 0)
        repo.updateStatus(ids, "read")
        // A message request gets no receipt until it is accepted.
        if (!chat.isRequest && ids.isNotEmpty()) { receipts.read(chatId, ids); requestFlush() }
    }

    private fun peerMessageIds(chatId: String, include: (MessageRecord) -> Boolean): List<String> =
        repo.messages(chatId, null, 500).filter {
            it.senderAccount != device.accountId && it.senderAccount != ChatMapping.SYSTEM_SENDER &&
                !it.id.startsWith(ChatMapping.CALL_PREFIX) && !it.undecryptable && include(it)
        }.map { it.id }

    override suspend fun addContact(username: String): Contact? {
        val name = username.trim().lowercase(Locale.ROOT)
        if (name.isEmpty()) return null
        val user = try { social.lookUpUser(name) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return null
        val row = ContactRecord(user.accountId, user.username, user.displayName, true, contactRows[user.accountId]?.addedAt ?: nowMs())
        repo.upsertContact(row)
        reload()
        return contactOf(row)
    }

    override fun ensureChat(contactId: String): String {
        ensureChatRow(contactId)
        reload()
        return contactId
    }

    private fun ensureChatRow(contactId: String): Boolean = try {
        if (repo.chat(contactId) == null) {
            val known = repo.contact(contactId)
            if (known == null) repo.upsertContact(ContactRecord(contactId, "", "", false, nowMs()))
            repo.upsertChat(ChatRecord(contactId, isRequest = known?.isContact != true, unread = 0, muted = false, lastActivity = nowMs()))
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun bumpActivity(chatId: String, at: Long) {
        val chat = repo.chat(chatId) ?: return
        if (at > chat.lastActivity) repo.upsertChat(chat.copy(lastActivity = at))
    }

    // Folders

    override fun addFolder(name: String, chatIds: Set<String>): ChatFolder {
        val folder = ChatFolder("folder-${freshId()}", name, ChatFolder.Kind.Custom)
        try {
            repo.transaction {
                repo.addFolder(FolderRecord(folder.id, name, repo.folders().size))
                repo.setFolderChats(folder.id, chatIds.sorted())
            }
        } catch (_: Exception) { }
        reload()
        return folder
    }

    override fun renameFolder(id: String, name: String) { repo.renameFolder(id, name); reload() }
    override fun deleteFolder(id: String) { repo.deleteFolder(id); reload() }
    override fun moveFolder(id: String, toCustomIndex: Int) { repo.moveFolder(id, toCustomIndex); reload() }

    // Pending block and report requests: kept in `kv`, retried until the server accepts them.

    private fun kickPending() {
        if (closed.get()) return
        inFlight.update { it + 1 }
        scope.launch {
            try { runPending(); reload() } finally { inFlight.update { it - 1 } }
        }
    }

    /** One run at a time; a request that comes in during a run makes it go round again. */
    private suspend fun runPending() {
        if (!pendingRunning.compareAndSet(false, true)) { pendingAgain.set(true); return }
        try {
            do {
                pendingAgain.set(false)
                runPendingOnce()
            } while (pendingAgain.getAndSet(false))
        } finally {
            pendingRunning.set(false)
        }
    }

    private suspend fun runPendingOnce() {
        for ((key, value) in repo.entries(BLOCK_PREFIX).filterValues { it.isNotEmpty() }.toSortedMap()) {
            try { social.block(key.removePrefix(BLOCK_PREFIX)) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
            repo.set(key, ByteArray(0))
        }
        for ((key, value) in repo.entries(PENDING_REPORT_PREFIX).filterValues { it.isNotEmpty() }.toSortedMap()) {
            val report = runCatching { Json.decodeFromString(PendingReport.serializer(), value.decodeToString()) }.getOrNull()
            if (report == null) { repo.set(key, ByteArray(0)); continue }
            try {
                social.report(report.accountId, report.conversationId, report.lines.map { ReportedLine(it.sentAt, it.text) })
            } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
            repo.set(key, ByteArray(0))
        }
    }

    @Serializable private class PendingLine(val sentAt: Long, val text: String)
    @Serializable private class PendingReport(val accountId: String, val conversationId: String?, val lines: List<PendingLine>)

    companion object {
        const val CACHED_MESSAGES = 50
        private const val REPORTED_MESSAGES = 10
        const val BLOCK_PREFIX = "pending.block."
        const val PENDING_REPORT_PREFIX = "pending.report."
        private const val REPORTED_PREFIX = "reported."
    }
}
