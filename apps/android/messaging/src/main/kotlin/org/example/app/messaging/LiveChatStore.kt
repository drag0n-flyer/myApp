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
import org.example.app.messaging.ports.AttachmentRecord
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
    suspend fun unblock(accountId: String)
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
    /**
     * Copies a `content:` or `file:` source into the given folder and returns the copy (null when it cannot be read or is
     * too large). A URI permission ends with the process, so the copy is made when the message is created.
     */
    private val stageSource: suspend (source: String, into: File) -> File? = { _, _ -> null },
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
    private val blobDir = dirs.blobDir
    private val tempDir = dirs.tempDir
    /** Ids of the attachment messages this process is preparing or uploading; a "sending" row not in here was cut off. */
    private val uploading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
    /** Only the queued writes (not sends or syncs): what [awaitWrites] waits for. */
    private val writesInFlight = MutableStateFlow(0)
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
                writesInFlight.update { it - 1 }
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

    override suspend fun awaitWrites() {
        writesInFlight.first { it == 0 }
    }

    /** Stops the store before its data is wiped (sign-out): later work does nothing. */
    suspend fun shutdown() {
        closed.set(true)
        settle()
    }

    private fun write(job: () -> Unit) {
        if (closed.get()) return
        inFlight.update { it + 1 }
        writesInFlight.update { it + 1 }
        if (writes.trySend(job).isFailure) { writesInFlight.update { it - 1 }; inFlight.update { it - 1 } }
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
            MessageHit(hit.chatId, hit.messageId, text, Instant.ofEpochMilli(record.sentAt))
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

    /** Messages that gave up sending (they show as [DeliveryStatus.Failed]; the UI offers [retrySend]). */
    fun failedMessageIds(chatId: String): Set<String> =
        repo.messages(chatId, null, 200).filter { it.status == "failed" && it.senderAccount == device.accountId }.map { it.id }.toSet()

    override fun retrySend(messageId: String) = write {
        if (repo.get(PENDING_UPLOAD_PREFIX + messageId)?.isNotEmpty() == true) retryUpload(messageId)
        else if (outbox.retry(messageId)) requestFlush()
    }

    /** Where what a message still needs for "Try again" is kept: in the media folder, which a sign-out wipes and the start-up sweep leaves alone. */
    private fun pendingDir(id: String) = File(blobDir.parentFile ?: blobDir, "pending/$id")

    /**
     * [path] is the picked or recorded file; [MediaPreparer] turns it into the upload. First the file is moved (a staged
     * copy in the temp folder) or copied (a `content:` URI, through [stageSource]) into [pendingDir], because the temp
     * folder is swept at start-up and a URI permission ends with the process. Then the message is stored as `sending`,
     * with the file's real name and size, so it shows at once; a failed upload makes it `failed` and keeps the files for
     * "Try again" ([retryUpload]); a file that can never be sent is removed ([dropUnsendable]).
     */
    override fun sendAttachment(path: String, kind: MediaKind, chatId: String) {
        if (closed.get()) return
        inFlight.update { it + 1 }
        scope.launch {
            val id = freshId()
            var stored = false
            try {
                if (!ensureChatRow(chatId)) return@launch
                uploading += id
                val staged = stage(id, path) ?: return@launch
                val at = nowMs()
                val pending = PendingUpload(kind.name, chatId, staged.path)
                repo.transaction {
                    repo.insertMessage(
                        MessageRecord(
                            id = id, chatId = chatId, conversationId = null, senderAccount = device.accountId, senderDevice = device.deviceId,
                            sentAt = at, receivedAt = at,
                            envelope = ContentCodec.encode(envelopeFor(kind, id, at, placeholderAtt(staged.length()), PreparedMedia(staged))),
                            status = "sending", replyTo = null, folded = "",
                        ),
                    )
                    repo.set(PENDING_UPLOAD_PREFIX + id, Json.encodeToString(PendingUpload.serializer(), pending).encodeToByteArray())
                }
                stored = true
                bumpActivity(chatId, at)
                reload()
                upload(id, at, pending)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (stored) failUpload(id)
            } finally {
                if (!stored) pendingDir(id).deleteRecursively()
                uploading -= id
                reload()
                inFlight.update { it - 1 }
            }
        }
    }

    /** The file as it is kept for this message: null when there is nothing to send. */
    private suspend fun stage(id: String, path: String): File? {
        if (path.startsWith("content:") || path.startsWith("file:")) {
            val dir = pendingDir(id)
            dir.mkdirs()
            val copy = try {
                stageSource(path, dir)
            } catch (e: CancellationException) {
                dir.deleteRecursively()
                throw e
            } catch (_: Exception) {
                null
            }
            if (copy == null || !copy.isFile) dir.deleteRecursively()
            return copy?.takeIf { it.isFile }
        }
        val file = File(path)
        if (!file.isFile) return null
        return if (isInsideTemp(file)) moveInto(pendingDir(id), file) else file
    }

    private fun isInsideTemp(file: File): Boolean = try {
        file.canonicalFile.toPath().startsWith(tempDir.canonicalFile.toPath())
    } catch (_: java.io.IOException) {
        false
    }

    private fun moveInto(dir: File, file: File): File {
        dir.mkdirs()
        val target = File(dir, file.name)
        if (file.canonicalFile == target.canonicalFile) return file
        return try {
            if (!file.renameTo(target)) {
                file.copyTo(target, overwrite = true)
                file.delete()
            }
            target
        } catch (_: java.io.IOException) {
            target.delete()
            file
        }
    }

    /** "Try again" on a message that never uploaded: uploads the kept file once, however many times it is tapped. */
    private fun retryUpload(messageId: String) {
        if (closed.get() || !uploading.add(messageId)) return
        val row = repo.message(messageId)
        val pending = repo.get(PENDING_UPLOAD_PREFIX + messageId)?.let {
            runCatching { Json.decodeFromString(PendingUpload.serializer(), it.decodeToString()) }.getOrNull()
        }
        if (row == null || pending == null || row.deleted || row.status != "failed" || row.senderAccount != device.accountId) {
            if (row == null || row.deleted) discardPending(messageId)
            uploading -= messageId
            return
        }
        repo.updateStatus(listOf(messageId), "sending")
        inFlight.update { it + 1 }
        scope.launch {
            try {
                reload()
                upload(messageId, row.sentAt, pending)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failUpload(messageId)
            } finally {
                uploading -= messageId
                reload()
                inFlight.update { it - 1 }
            }
        }
    }

    /**
     * Prepares (unless a kept prepared file is there), uploads, then in one transaction: the final envelope, the outbox
     * row and the sender's own attachment record. A failure marks the message `failed` and keeps the files; a message
     * deleted meanwhile is not sent.
     */
    private suspend fun upload(id: String, at: Long, start: PendingUpload) {
        val kind = MediaKind.valueOf(start.kind)
        var pending = start
        val keptFile = pending.prepared.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }
        val prepared: PreparedMedia = if (keptFile != null) {
            preparedFrom(repo.message(id)?.envelope, keptFile)
        } else {
            val fresh = mediaPreparer.prepare(pending.path, kind)?.takeIf { it.file.isFile }
            if (fresh == null) {
                // Nothing to retry from: it can never send, so it is removed instead of offering a dead "Try again".
                dropUnsendable(id)
                return
            }
            val file = if (isInside(fresh.file, pendingDir(id))) fresh.file else if (isInsideTemp(fresh.file)) moveInto(pendingDir(id), fresh.file) else fresh.file
            val p = PreparedMedia(file, fresh.width, fresh.height, fresh.thumbnail, fresh.duration, fresh.name)
            pending = pending.copy(prepared = file.path)
            repo.transaction {
                repo.replaceUnsentEnvelope(id, device.accountId, ContentCodec.encode(envelopeFor(kind, id, at, placeholderAtt(file.length()), p)), "", nowMs())
                repo.set(PENDING_UPLOAD_PREFIX + id, Json.encodeToString(PendingUpload.serializer(), pending).encodeToByteArray())
            }
            reload()
            p
        }
        // The ciphertext waits in the temp folder until the message is stored, so no blob is ever left without a record.
        val uploaded = try {
            uploader.uploadKeeping(prepared.file)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failUpload(id)
            return
        }
        val att = uploaded.att
        val envelope = envelopeFor(kind, id, at, att, prepared)
        val queued = try {
            repo.transaction {
                // False when the message was deleted while it uploaded: then nothing is queued and no record is written.
                if (!repo.replaceUnsentEnvelope(id, device.accountId, ContentCodec.encode(envelope), fold(prepared.name.takeIf { kind == MediaKind.File } ?: ""), nowMs())) {
                    false
                } else {
                    outbox.enqueue(envelope, pending.chatId)
                    // The sender's own copy: the same record a received file gets, LOCAL at the ciphertext the upload kept.
                    repo.upsertAttachment(
                        AttachmentRecord(att.id, id, attachmentKind(kind), att.key, att.dg, att.sz, att.csz, AttachmentState.LOCAL, att.csz, Uploader.blobFile(blobDir, att).path),
                    )
                    repo.set(PENDING_UPLOAD_PREFIX + id, ByteArray(0))
                    true
                }
            }
        } catch (e: Throwable) {
            uploaded.discard()
            throw e
        }
        if (!queued) {
            uploaded.discard()
            discardPending(id, prepared.file, pending.path)
            reload()
            return
        }
        try {
            uploaded.moveTo(blobDir)
        } catch (e: Exception) {
            uploaded.discard()
            try { repo.setAttachmentState(att.id, AttachmentState.FAILED, 0, null) } catch (_: Exception) { }
        }
        discardPending(id, prepared.file, pending.path)
        bumpActivity(pending.chatId, at)
        reload()
        requestFlush()
    }

    private fun failUpload(id: String) {
        try { repo.updateStatus(listOf(id), "failed") } catch (_: Exception) { }
    }

    /** A message that can never be sent: its row, its pending key and its kept files go. No "Try again" is left for it. */
    private fun dropUnsendable(id: String) {
        try {
            repo.transaction {
                repo.deleteUnsentMessage(id, device.accountId)
                repo.set(PENDING_UPLOAD_PREFIX + id, ByteArray(0))
            }
        } catch (_: Exception) {
            failUpload(id)
        }
        pendingDir(id).deleteRecursively()
        reload()
    }

    /**
     * Removes what was kept for [id]: its folder, its pending key and, given the files, the prepared file and the
     * staged copy when it is a file inside the temp folder (never a `content:` URI or a path that leaves it).
     */
    private fun discardPending(id: String, prepared: File? = null, path: String? = null) {
        try { repo.set(PENDING_UPLOAD_PREFIX + id, ByteArray(0)) } catch (_: Exception) { }
        pendingDir(id).deleteRecursively()
        prepared?.let { if (isInsideTemp(it)) it.delete() }
        if (path != null && !path.startsWith("content:") && !path.startsWith("file:")) {
            val file = File(path)
            if (file.isFile && isInsideTemp(file)) file.delete()
        }
    }

    private fun isInside(file: File, dir: File): Boolean = try {
        file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())
    } catch (_: java.io.IOException) {
        false
    }

    /** The stand-in until the upload gives the real one: no id, but the real size, so the bubble never shows 0 B. */
    private fun placeholderAtt(size: Long) = Att("", ByteArray(0), ByteArray(0), size, size)

    private fun envelopeFor(kind: MediaKind, id: String, at: Long, att: Att, p: PreparedMedia): ContentEnvelope = when (kind) {
        MediaKind.Photo, MediaKind.Video -> ContentEnvelope.Photo(id, at, att, p.width, p.height, p.thumbnail)
        MediaKind.Voice -> ContentEnvelope.Voice(id, at, att, p.duration)
        MediaKind.File -> ContentEnvelope.File(id, at, att, p.name, "application/octet-stream")
    }

    /** What a retry knows about the prepared file: its length, size or name, from the stored envelope. */
    private fun preparedFrom(envelope: ByteArray?, file: File): PreparedMedia = when (val e = envelope?.let { ContentCodec.decode(it) }) {
        is ContentEnvelope.Photo -> PreparedMedia(file, e.w, e.h, e.th)
        is ContentEnvelope.Voice -> PreparedMedia(file, duration = e.dur)
        is ContentEnvelope.File -> PreparedMedia(file, name = e.name)
        else -> PreparedMedia(file)
    }

    /**
     * Messages still `sending` whose upload died with the last process become `failed`, so "Try again" is offered. A
     * kept folder with no pending key (killed before its message was stored) is removed.
     */
    private fun failInterruptedUploads() {
        for ((key, value) in repo.entries(PENDING_UPLOAD_PREFIX).filterValues { it.isNotEmpty() }) {
            val id = key.removePrefix(PENDING_UPLOAD_PREFIX)
            if (id in uploading) continue
            if (repo.message(id)?.status == "sending") repo.updateStatus(listOf(id), "failed")
        }
        File(blobDir.parentFile ?: blobDir, "pending").listFiles()?.forEach { dir ->
            if (dir.name !in uploading && repo.get(PENDING_UPLOAD_PREFIX + dir.name)?.isNotEmpty() != true) dir.deleteRecursively()
        }
    }

    private fun attachmentKind(kind: MediaKind) = when (kind) {
        MediaKind.Photo, MediaKind.Video -> "photo"
        MediaKind.Voice -> "voice"
        MediaKind.File -> "file"
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

    /**
     * Decrypts a downloaded attachment for viewing. Close the result when it is no longer shown. Null when the store is
     * closed, or the attachment is not [AttachmentState.LOCAL] with its blob on disk: opening never downloads (that is
     * [download]), and while it runs a sign-out wipe waits for it.
     */
    suspend fun openAttachment(messageId: String): OpenedAttachment? {
        if (closed.get()) return null
        inFlight.update { it + 1 }
        try {
            if (closed.get()) return null
            val r = repo.attachmentForMessage(messageId) ?: return null
            if (r.state != AttachmentState.LOCAL || r.localPath?.let { File(it).isFile } != true) return null
            return try { downloader.open(Att(r.id, r.key, r.digest, r.size, r.cipherSize)) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        } finally {
            inFlight.update { it - 1 }
        }
    }

    // Start-up (task I1)

    /** Finishes every conversation create that was cut short. Call it at start-up after the first inbox sync. */
    suspend fun resumePendingCreates() {
        conversations.resumePendingCreates()
        reload()
    }

    /** Removes decrypted files and partial downloads a killed process left: `Downloader.sweepOpen`, then `MediaCache.sweep`. */
    fun sweepStartup() {
        failInterruptedUploads()
        reload()
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
        // An attachment that never uploaded keeps nothing; one that is uploading cleans up when it finishes.
        if (messageId !in uploading && repo.get(PENDING_UPLOAD_PREFIX + messageId)?.isNotEmpty() == true) discardPending(messageId)
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

    /** The members of one of the chat's groups could not be read. A database error counts as unreadable. */
    override fun membersUnreadable(chatId: String): Boolean =
        runCatching { Membership.membersUnreadable(chatId, repo) }.getOrDefault(true)

    /** Hidden, and not verifiable, while the chat's members cannot be read. */
    override fun safetyNumber(chatId: String): SafetyNumber? {
        if (!supportsSafetyNumbers || membersUnreadable(chatId)) return null
        val row = repo.safety(chatId) ?: return null
        return SafetyNumber(ChatMapping.groups(row.current), row.verified == row.current)
    }

    /** Local only: nothing is sent. */
    override fun setVerified(verified: Boolean, chatId: String) {
        if (!supportsSafetyNumbers || membersUnreadable(chatId)) return
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
            repo.set(UNBLOCK_PREFIX + contactId, ByteArray(0))
        }
        kickPending()
    }

    /** Local first, like [block]: the server call is kept in `kv` and retried until it is accepted. */
    override fun unblock(contactId: String) = write {
        repo.transaction {
            repo.unblock(contactId)
            repo.set(BLOCK_PREFIX + contactId, ByteArray(0)) // a block the server never got is dropped
            repo.set(UNBLOCK_PREFIX + contactId, byteArrayOf(1))
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
        for ((key, _) in repo.entries(UNBLOCK_PREFIX).filterValues { it.isNotEmpty() }.toSortedMap()) {
            try { social.unblock(key.removePrefix(UNBLOCK_PREFIX)) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
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

    /** What "Try again" needs for an attachment that has not uploaded: the picked file, and the prepared file once there is one. */
    @Serializable private data class PendingUpload(val kind: String, val chatId: String, val path: String, val prepared: String = "")
    @Serializable private class PendingLine(val sentAt: Long, val text: String)
    @Serializable private class PendingReport(val accountId: String, val conversationId: String?, val lines: List<PendingLine>)

    companion object {
        const val CACHED_MESSAGES = 50
        private const val REPORTED_MESSAGES = 10
        const val BLOCK_PREFIX = "pending.block."
        const val UNBLOCK_PREFIX = "pending.unblock."
        const val PENDING_REPORT_PREFIX = "pending.report."
        private const val PENDING_UPLOAD_PREFIX = "pending.upload."
        private val NO_ATT = Att("", ByteArray(0), ByteArray(0), 0, 0)
        private const val REPORTED_PREFIX = "reported."
    }
}
