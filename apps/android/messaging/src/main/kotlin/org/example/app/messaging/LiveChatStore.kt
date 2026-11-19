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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.example.app.core.chats.ChatMedia
import org.example.app.core.groups.ChatID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.example.app.core.chats.LinkPreviewRule
import org.example.app.core.chats.SendPlan
import org.example.app.core.chats.UploadCancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
import org.example.app.core.chats.ChatListOrder
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.GroupChangeFailed
import org.example.app.core.chats.GroupCreateFailed
import org.example.app.core.chats.GroupCreated
import org.example.app.core.chats.Contact
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.IncomingCallDecision
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.MessageHit
import org.example.app.core.chats.MessagePreview
import org.example.app.core.chats.SafetyNumber
import org.example.app.core.chats.SendProblem
import org.example.app.core.people.Expiry
import org.example.app.core.settings.MediaKind
import org.example.app.messaging.content.Att
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.content.EditRule
import org.example.app.messaging.content.LinkPreview
import org.example.app.messaging.content.TextLimit
import org.example.app.messaging.media.Downloader
import org.example.app.messaging.media.OpenedAttachment
import org.example.app.messaging.media.Uploader
import org.example.app.messaging.ports.AttachmentRecord
import org.example.app.messaging.ports.AttachmentState
import org.example.app.messaging.ports.CallRecord
import org.example.app.messaging.ports.ChatKind
import org.example.app.messaging.ports.ChatRecord
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.ports.FolderRecord
import org.example.app.messaging.ports.MessageRecord
import org.example.app.messaging.ports.MlsLock
import org.example.app.messaging.ports.MyState
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
    val duration: Double = 0.0, val name: String = file.name, val mime: String = "",
)

fun interface MediaPreparer {
    /** Null when the file cannot be sent. */
    suspend fun prepare(path: String, kind: MediaKind): PreparedMedia?
}

/** What [LiveChatStore]'s `stageSource` makes of a `content:` or `file:` source. */
sealed interface StageResult {
    class Copied(val file: File) : StageResult
    data object TooBig : StageResult
    data object Unreadable : StageResult
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
    private val dirs: MediaDirs,
    private val social: SocialPort,
    private val scope: CoroutineScope,
    private val fold: (String) -> String = { it.lowercase(Locale.ROOT) },
    private val mediaPreparer: MediaPreparer = MediaPreparer { path, kind -> if (kind == MediaKind.File) PreparedMedia(File(path)) else null },
    /**
     * Copies a `content:` or `file:` source into the given folder and returns the copy, or why it cannot (too large, not
     * readable); the person is told through [sendProblem]. A URI permission ends with the process, so the copy is made
     * when the message is created.
     */
    private val stageSource: suspend (source: String, into: File) -> StageResult = { _, _ -> StageResult.Unreadable },
    private val mediaSweep: () -> Unit = {},
    onEscrowRejected: () -> Unit = {},
) : ChatStore, CallSignalSealer by sealer {

    /** Messages made one after the other keep their order, and the time never runs ahead of the device clock for long. */
    private val storeClock: Clock = MonotonicClock(clock)

    val outbox = Outbox(
        device, mls, server, repo, lock, storeClock, conversations, escrow, syncOnce = { inbox.sync() },
        readReceiptsEnabled = readReceiptsEnabled, fold = fold, onEscrowRejected = onEscrowRejected,
    )
    val receipts = Receipts(outbox, readReceiptsEnabled)
    val downloader = Downloader(server, dirs.blobDir, dirs.openDir, maxAttachmentBytes)
    private val uploader = Uploader(server, dirs.tempDir)
    private val blobDir = dirs.blobDir
    private val tempDir = dirs.tempDir
    /** Ids of the attachment messages this process is preparing or uploading; a "sending" row not in here was cut off. */
    private val uploading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** The running upload job of each message in [uploading], so Cancel can stop it; and the ones Cancel was pressed for. */
    private val uploadJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val progress = MutableStateFlow<Map<String, Double>>(emptyMap())
    override val uploadProgress: StateFlow<Map<String, Double>> = progress.asStateFlow()

    private fun setProgress(id: String, fraction: Double) {
        // Whole percents are enough for a ring; the stream reports on every read.
        progress.update { if (fraction >= 1.0 || (it[id] ?: -1.0) + 0.01 <= fraction) it + (id to fraction) else it }
    }

    override fun cancelUpload(messageId: String) = write {
        val pending = repo.get(PENDING_UPLOAD_PREFIX + messageId)?.isNotEmpty() == true
        if (UploadCancel.onCancel(if (pending) UploadCancel.Phase.Uploading else UploadCancel.Phase.Sent) == UploadCancel.Action.Keep) return@write
        val job = uploadJobs[messageId]
        if (job != null) {
            // The job removes everything itself when it sees the cancellation.
            cancelled += messageId
            // A cancel that lost the race (the job finished without seeing it) leaves nothing behind.
            job.invokeOnCompletion { cancelled -= messageId }
            job.cancel()
        } else {
            removeUnsent(messageId)
        }
    }

    /**
     * Cancel: removes the message, its pending key, its kept and staged files, in one transaction for the rows. Does
     * nothing when the message was queued meanwhile (its pending key is already empty): it is sent then.
     */
    private fun removeUnsent(id: String) {
        val raw = try {
            repo.transaction {
                val value = repo.get(PENDING_UPLOAD_PREFIX + id)
                if (value == null || value.isEmpty() || !repo.deleteUnsentMessage(id, device.accountId)) null
                else value.also { repo.set(PENDING_UPLOAD_PREFIX + id, ByteArray(0)) }
            }
        } catch (_: Exception) { null } ?: return
        val pending = runCatching { Json.decodeFromString(PendingUpload.serializer(), raw.decodeToString()) }.getOrNull()
        discardPending(id, pending?.prepared?.takeIf { it.isNotEmpty() }?.let(::File), pending?.path)
        reload()
    }

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    private val _folders = MutableStateFlow(listOf(ChatFolder.All, ChatFolder.Unread))
    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    private val _requests = MutableStateFlow<List<Chat>>(emptyList())
    private val _muted = MutableStateFlow<Set<String>>(emptySet())
    private val profilesFlow = MutableStateFlow<Map<String, org.example.app.core.people.Profile>>(emptyMap())
    override val profiles: StateFlow<Map<String, org.example.app.core.people.Profile>> = profilesFlow.asStateFlow()
    @Volatile private var profileNames: Map<String, String> = emptyMap()
    /** Profile names of group members whose one-to-one chat is a message request: used for group lines only. */
    @Volatile private var groupOnlyNames: Map<String, String> = emptyMap()

    private val profileSender = org.example.app.messaging.send.ProfileSender(repo, device.accountId, { runCatching { repo.contact(device.accountId)?.displayName }.getOrNull().orEmpty() }) { chatId, name, photo, ver ->
        outbox.enqueueControl(chatId) { id, at -> ContentEnvelope.Profile(id, at, name, photo, ver) }
    }

    // Declared before the first `init` block: a flush can start while the store is still being built, and it calls this.
    private val groupCreator = org.example.app.messaging.sync.GroupCreator(conversations, repo, outbox, ownProfile = { id, at -> profileSender.envelope(id, at) })

    private val _blocked = MutableStateFlow<Set<String>>(emptySet())
    private val _reported = MutableStateFlow<Set<String>>(emptySet())
    override val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()
    override val folders: StateFlow<List<ChatFolder>> = _folders.asStateFlow()
    override val chats: StateFlow<List<Chat>> = _chats.asStateFlow()
    override val requests: StateFlow<List<Chat>> = _requests.asStateFlow()
    override val blockedIds: StateFlow<Set<String>> = _blocked.asStateFlow()
    override val reportedChatIds: StateFlow<Set<String>> = _reported.asStateFlow()
    override val mutedChatIds: StateFlow<Set<String>> = _muted.asStateFlow()

    /** The last dropped attachment per chat, kept until the chat screen has shown it ([takeSendProblem]). */
    private val problems = MutableStateFlow<Map<String, SendProblem.Reason>>(emptyMap())
    override fun sendProblem(chatId: String): Flow<SendProblem.Reason?> = problems.map { it[chatId] }.distinctUntilChanged()
    override fun takeSendProblem(chatId: String) = problems.update { it - chatId }
    private fun raise(chatId: String, reason: SendProblem.Reason) { if (!closed.get()) problems.update { it + (chatId to reason) } }

    private val meFlow = MutableStateFlow(Contact(device.accountId, "You", ""))
    private val meContact: Contact get() = meFlow.value
    @Volatile private var contactRows: Map<String, ContactRecord> = emptyMap()
    override val me: Contact get() = meContact
    override val meState: StateFlow<Contact> = meFlow.asStateFlow()
    override val myStoredName: String get() = contactRows[device.accountId]?.displayName.orEmpty()

    // Declared before `init`, whose first reload reads them.
    private val pinLock = Any()
    /** Pins and unpins asked for but not yet written: true pinned, false unpinned. Counted by the limit of five. */
    private val pinsPending = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** The latest unsent text per chat that is not written yet; blank means remove. */
    private val draftsPending = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val reloadLock = Any()
    /** A queued database write; [reload] false for the ones that do not change what the lists show (see [setDraft]). */
    private class Write(val job: () -> Unit, val reload: Boolean)
    private val writes = Channel<Write>(Channel.UNLIMITED)
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
            for (item in writes) {
                try { item.job() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                if (item.reload) reload()
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
        problems.value = emptyMap() // nothing of this account is kept past sign-out
    }

    private fun write(reload: Boolean = true, job: () -> Unit) {
        if (closed.get()) return
        inFlight.update { it + 1 }
        writesInFlight.update { it + 1 }
        if (writes.trySend(Write(job, reload)).isFailure) { writesInFlight.update { it - 1 }; inFlight.update { it - 1 } }
    }

    private fun requestFlush() {
        if (closed.get()) return
        flushWanted.set(true)
        if (!flushRunning.compareAndSet(false, true)) return
        inFlight.update { it + 1 }
        scope.launch {
            try {
                try {
                    while (flushWanted.getAndSet(false)) {
                        // A group whose mode post failed at creation: its `group` and `profile` envelopes are queued now (a marker, retried here).
                        groupCreator.resumeInit()
                        try { outbox.flush() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                        runPending()
                        reload()
                    }
                } finally {
                    flushRunning.set(false)
                }
                // A request that came in after the last check but before the flag was cleared. It is counted in
                // flight before this run's own count is released, so settle() never sees a gap between the two.
                if (flushWanted.get()) requestFlush()
            } finally {
                inFlight.update { it - 1 }
            }
        }
    }

    private fun nowMs() = storeClock.now()
    private fun freshId() = UUID.randomUUID().toString().lowercase()
    private val showRead get() = readReceiptsEnabled()

    private fun reload() = synchronized(reloadLock) {
        val rows = runCatching { repo.contacts() }.getOrDefault(emptyList())
        contactRows = rows.associateBy { it.accountId }
        loadProfiles()
        contactRows[device.accountId]?.let { meFlow.value = Contact(it.accountId, org.example.app.core.profile.DisplayNameRule.forDisplay(it.displayName, "").ifEmpty { "You" }, it.username) }
        _contacts.value = rows.filter { it.isContact }.sortedWith(compareBy({ it.displayName }, { it.accountId })).map(::contactOf)

        val folderRecords = runCatching { repo.folders() }.getOrDefault(emptyList())
        _folders.value = listOf(ChatFolder.All, ChatFolder.Unread) + folderRecords.map { ChatFolder(it.id, it.name, ChatFolder.Kind.Custom) }
        val folderIds = HashMap<String, MutableSet<String>>()
        for (f in folderRecords) for (chatId in runCatching { repo.folderChats(f.id) }.getOrDefault(emptyList())) {
            folderIds.getOrPut(chatId) { mutableSetOf() } += f.id
        }

        // An expired message is not counted before the sweep removes it.
        val expiredUnread = runCatching { repo.expiredUnread(nowMs(), device.accountId) }.getOrDefault(emptyMap())
        val records = runCatching { repo.chats() }.getOrDefault(emptyList())
            .map { it.copy(unread = maxOf(0, it.unread - (expiredUnread[it.id] ?: 0))) }
            .sortedByDescending { it.lastActivity }
        // Saved drafts, with the ones still waiting to be written laid over them so a reload cannot show an older text.
        val drafts = runCatching { repo.drafts() }.getOrDefault(emptyMap()).toMutableMap()
        for ((chatId, text) in draftsPending) if (text.isBlank()) drafts.remove(chatId) else drafts[chatId] = text
        val pinned = runCatching { pinsPending.toMap() }.getOrDefault(emptyMap())
        // A draft typed before its chat existed: write it now that the row is here.
        val rowIds = records.map { it.id }.toSet()
        for ((id, text) in draftsPending) if (text.isNotEmpty() && id in rowIds) write(reload = false) { persistDraft(id) }
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
            val chat = ChatMapping.chat(record, all, preferred, folderIds[record.id].orEmpty(), map(latest))
            val pinnedAt = when (pinned[record.id]) {
                null -> chat.pinnedAt
                true -> chat.pinnedAt ?: Instant.ofEpochMilli(nowMs())
                false -> null
            }
            chat.copy(draft = drafts[record.id], pinnedAt = pinnedAt, group = groupInfoOf(record))
        }
        // Pinned chats first (the newest pin on top); the rest keep their activity order.
        val (pinnedChats, otherChats) = built.filter { !it.isRequest }.partition { it.pinnedAt != null }
        _chats.value = pinnedChats.sortedWith(compareByDescending<Chat> { it.pinnedAt }.thenBy { it.id }) + otherChats
        _muted.value = records.filter { it.muted }.map { it.id }.toSet()
        _requests.value = built.filter { it.isRequest }
        _blocked.value = runCatching { repo.blockedIds().toSet() }.getOrDefault(emptySet())
        if (!closed.get() && runCatching { profileSender.hasPending() }.getOrDefault(false)) write(reload = false) { profileSender.sync(); requestFlush() }
        _reported.value = runCatching { repo.entries(REPORTED_PREFIX).filterValues { it.isNotEmpty() }.keys.map { it.removePrefix(REPORTED_PREFIX) }.toSet() }
            .getOrDefault(emptySet())
    }

    private fun map(records: List<MessageRecord>): List<Message> {
        val showSafety = supportsSafetyNumbers
        val show = showRead
        val members = HashMap<String, List<org.example.app.core.groups.Member>>()
        // One query for every receipt of my group messages in this batch, not one per message.
        val receiptsByMessage = repo.receiptsFor(
            records.filter { ChatID.isGroup(it.chatId) && it.senderAccount == device.accountId }.map { it.id },
        ).groupBy { it.messageId }
        return records.map { record ->
            val plain = record.senderAccount != ChatMapping.SYSTEM_SENDER && !record.id.startsWith(ChatMapping.CALL_PREFIX)
            ChatMapping.message(
                record,
                attachment = if (plain) repo.attachmentForMessage(record.id) else null,
                reactions = if (plain && !record.deleted) repo.reactions(record.id) else emptyList(),
                showRead = show,
                displayName = { account -> personName(account) },
                groupStatus = if (plain) groupTick(record, members, receiptsByMessage) else null,
            )
        }.filter { showSafety || it.content != MessageContent.System(org.example.app.core.chats.SystemEvent.SafetyNumberChanged) }
    }

    private fun nameOf(row: ContactRecord?): String? {
        if (row == null) return null
        // Names come from the server and from other people's devices: cleaned for every screen and notification.
        // A contact's own profile name comes first; the lookup name is the fallback.
        val profileName = if (row.accountId == device.accountId) null else profileNames[row.accountId]
        return org.example.app.core.people.ContactDisplay.name(null, profileName, row.displayName, row.username).ifEmpty { null }
    }

    /** A contact's name by the usual rule, else a group member's own (cleaned) profile name; null when neither is known. */
    override fun personName(accountId: String): String? =
        nameOf(contactRows[accountId]) ?: (profileNames[accountId] ?: groupOnlyNames[accountId])?.let { org.example.app.core.profile.DisplayNameRule.forDisplay(it, "") }?.ifEmpty { null }

    override fun messageReceipts(messageId: String): List<org.example.app.core.chats.MessageReceipt> {
        val record = repo.message(messageId) ?: return emptyList()
        if (!ChatID.isGroup(record.chatId) || record.senderAccount != device.accountId) return emptyList()
        val shownRead = showRead
        return repo.receipts(messageId).groupBy { it.accountId }.map { (account, list) ->
            val read = list.firstOrNull { it.kind == "read" }?.at?.takeIf { shownRead }
            val delivered = list.firstOrNull { it.kind == "delivered" }?.at ?: read
            org.example.app.core.chats.MessageReceipt(account, delivered?.let(Instant::ofEpochMilli), read?.let(Instant::ofEpochMilli))
        }.sortedBy { it.accountId }
    }

    /** The tick of my message in a group, from the members' receipts; null for a message that has not left yet or is not mine in a group. */
    private fun groupTick(
        record: MessageRecord, members: MutableMap<String, List<org.example.app.core.groups.Member>>,
        receiptsByMessage: Map<String, List<org.example.app.messaging.ports.ReceiptRecord>>,
    ): DeliveryStatus? {
        if (!ChatID.isGroup(record.chatId) || record.senderAccount != device.accountId || record.status == "sending" || record.status == "failed") return null
        val list = members.getOrPut(record.chatId) {
            repo.groupMembers(record.chatId).mapNotNull { m -> org.example.app.core.groups.GroupRole.from(m.role)?.let { org.example.app.core.groups.Member(m.accountId, it) } }
        }
        val state = org.example.app.core.groups.GroupTicks.state(
            (receiptsByMessage[record.id] ?: emptyList()).map { org.example.app.core.groups.GroupReceipt(it.accountId, it.kind) }, list, device.accountId,
        )
        return when (state) {
            org.example.app.core.groups.GroupTicks.State.Sent -> DeliveryStatus.Sent
            org.example.app.core.groups.GroupTicks.State.Delivered -> DeliveryStatus.Delivered
            // Like a one-to-one chat: with the Read receipts switch off, read shows as delivered.
            org.example.app.core.groups.GroupTicks.State.Read -> if (showRead) DeliveryStatus.Read else DeliveryStatus.Delivered
        }
    }

    // Profiles: contacts' newest, and the person's own, kept for the tile and name helpers.

    private fun loadProfiles() {
        val loaded = HashMap<String, org.example.app.core.people.Profile>()
        // A profile from a blocked account is never shown, and one from a message request waits until it is accepted.
        val blocked = runCatching { repo.blockedIds().toSet() }.getOrDefault(emptySet())
        val requestIds = runCatching { repo.chats().filter { it.isRequest }.map { it.id }.toSet() }.getOrDefault(emptySet())
        val hidden = blocked + requestIds
        val groupOnly = HashMap<String, String>()
        for (id in contactRows.keys) {
            if (id == device.accountId || id in hidden) continue
            runCatching { repo.profile(id) }.getOrNull()?.let { loaded[id] = org.example.app.core.people.Profile(it.accountId, it.name, it.photo, it.ver) }
        }
        // The people in my groups, contact or not: their own name and photo for the sender line and the tile.
        val inGroups = runCatching { repo.chats().filter { it.kind == org.example.app.messaging.ports.ChatKind.GROUP }.flatMap { repo.groupMembers(it.id) } }
            .getOrDefault(emptyList()).map { it.accountId }.toSet()
        for (id in inGroups) {
            if (id == device.accountId || id in blocked || id in loaded) continue
            val row = runCatching { repo.profile(id) }.getOrNull() ?: continue
            // A person whose one-to-one chat is still a message request: the name shows in the group only, never in the request.
            if (id in requestIds) groupOnly[id] = row.name
            else loaded[id] = org.example.app.core.people.Profile(row.accountId, row.name, row.photo, row.ver)
        }
        groupOnlyNames = groupOnly
        runCatching { profileSender.photo() }.getOrNull()?.let {
            loaded[device.accountId] = org.example.app.core.people.Profile(device.accountId, myStoredName, it, runCatching { profileSender.ver() }.getOrDefault(0))
        }
        profileNames = loaded.filterKeys { it != device.accountId }.mapValues { it.value.name }
        // A group's photo shows under the chat ID, only when it passes the profile photo rule (a row an earlier build stored is checked again).
        for (row in runCatching { repo.chats() }.getOrDefault(emptyList())) {
            val photo = row.photo ?: continue
            if (row.kind == ChatKind.GROUP && org.example.app.core.people.ProfilePhotoRule.isAcceptable(photo)) {
                loaded[row.id] = org.example.app.core.people.Profile(row.id, row.title.orEmpty(), photo, row.groupVer ?: 0)
            }
        }
        profilesFlow.value = loaded
    }

    /** A group chat's member list (roles the server gave, unknown roles left out) and whether this account is still in it; null for a one-to-one chat. */
    private fun groupInfoOf(record: ChatRecord): org.example.app.core.groups.GroupInfo? {
        if (record.kind != ChatKind.GROUP) return null
        val members = runCatching { repo.groupMembers(record.id) }.getOrDefault(emptyList()).mapNotNull { row ->
            org.example.app.core.groups.GroupRole.from(row.role)?.let { org.example.app.core.groups.Member(row.accountId, it) }
        }
        val state = when (record.myState) {
            MyState.REMOVED -> org.example.app.core.groups.MyGroupState.Removed
            MyState.LEFT -> org.example.app.core.groups.MyGroupState.Left
            else -> org.example.app.core.groups.MyGroupState.Member
        }
        return org.example.app.core.groups.GroupInfo(members, state)
    }

    /** Removes a group chat this account is no longer in, with its rows. A group it is still in, or a one-to-one chat, is left alone. */
    override fun deleteChat(chatId: String) = write {
        if (!ChatID.isGroup(chatId)) return@write
        repo.transaction {
            val row = repo.chat(chatId) ?: return@transaction
            if (row.kind == ChatKind.GROUP && row.myState != MyState.MEMBER) repo.deleteChat(chatId)
        }
    }

    override fun setMyPhoto(photo: ByteArray?) {
        if (photo != null && !org.example.app.core.people.ProfilePhotoRule.isAcceptable(photo)) return
        write { profileSender.setPhoto(photo); requestFlush() }
    }

    override fun announceProfile() = write { profileSender.announce(); requestFlush() }

    private fun contactOf(row: ContactRecord) = Contact(row.accountId, nameOf(row) ?: "Unknown", row.username)

    // Reads

    // Chat list actions. Pin, mute and archive are local to this device and change one column each, so an incoming
    // message (which only touches unread and last activity) can never undo them.

    override fun setPinned(chatId: String, pinned: Boolean): Boolean {
        synchronized(pinLock) {
            if (pinned) {
                if ((_chats.value + _requests.value).any { it.id == chatId && it.archived }) return false // an archived chat is never pinned
                // An archived chat is never pinned, so it never counts.
                val shown = (_chats.value + _requests.value).filter { it.pinnedAt != null && !it.archived }.map { it.id }.toMutableSet()
                for ((id, on) in pinsPending) if (on) shown += id else shown -= id
                if (chatId !in shown && shown.size >= ChatListOrder.MAX_PINS) return false
            }
            pinsPending[chatId] = pinned
        }
        val at = nowMs()
        write {
            val current = repo.chat(chatId) ?: return@write
            if (pinned && current.archived) return@write // archived after the check: nothing is written
            // Pinning an already pinned chat keeps its pin time.
            if (pinned) { if (current.pinnedAt == null) repo.setPinnedAt(chatId, at) } else repo.setPinnedAt(chatId, null)
        }
        // After the write and its reload, the stored value says the same: stop overriding.
        write(reload = false) { pinsPending.remove(chatId, pinned) }
        return true
    }

    override fun setMuted(chatId: String, muted: Boolean) = write { repo.setMuted(chatId, muted) }

    /** Archiving also unpins (one write); unarchiving does not pin again. */
    override fun setArchived(chatId: String, archived: Boolean) {
        if (archived) {
            synchronized(pinLock) { pinsPending[chatId] = false }
            write { repo.setArchived(chatId, true) }
            write(reload = false) { pinsPending.remove(chatId, false) }
        } else {
            write { repo.setArchived(chatId, false) }
        }
    }

    /**
     * Called on every keystroke, so it shows at once (the chat list sees "Draft:" the moment the chat is left) and the
     * write does not reload every list. Writes run in order, so the last text typed is the one stored.
     */
    override fun setDraft(chatId: String, text: String?) {
        val value = text?.takeIf { it.isNotBlank() } ?: ""
        draftsPending[chatId] = value
        val patch = { list: List<Chat> -> list.map { if (it.id == chatId) it.copy(draft = value.ifEmpty { null }) else it } }
        // Under the reload lock, so a reload that began earlier cannot publish its older draft after this one.
        synchronized(reloadLock) {
            _chats.update(patch)
            _requests.update(patch)
        }
        write(reload = false) { persistDraft(chatId) }
    }

    /**
     * Writes the newest pending text for the chat. A chat with no row yet keeps its draft in memory (the row is not
     * there for the draft to hang on); [reload] writes it as soon as the row exists. If it never does, it lives for the
     * process only.
     */
    private fun persistDraft(chatId: String) {
        val text = draftsPending[chatId] ?: return
        if (text.isEmpty()) {
            repo.setDraft(chatId, null, nowMs())
            draftsPending.remove(chatId, text)
        } else if (repo.chat(chatId) != null) {
            repo.setDraft(chatId, text, nowMs())
            draftsPending.remove(chatId, text)
        }
    }

    override fun chat(id: String): Chat? = _chats.value.firstOrNull { it.id == id } ?: _requests.value.firstOrNull { it.id == id }

    override fun contact(id: String): Contact? = if (id == device.accountId) meContact else contactRows[id]?.let(::contactOf)

    /** Shown at once and stored with the person's own contact row, in the reload's lock so a reload cannot put the old name back. */
    override fun setMyDisplayName(name: String) = synchronized(reloadLock) {
        val row = contactRows[device.accountId] ?: runCatching { repo.contact(device.accountId) }.getOrNull()
            ?: ContactRecord(device.accountId, meContact.username, name, false, nowMs())
        val updated = row.copy(displayName = name)
        runCatching { repo.upsertContact(updated) }
        contactRows = contactRows + (device.accountId to updated)
        meFlow.value = Contact(device.accountId, name.ifEmpty { "You" }, meContact.username)
    }

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
        val blockedNow = runCatching { repo.blockedIds().toSet() }.getOrDefault(emptySet())
        return repo.search(folded, limit).mapNotNull { hit ->
            if (hit.chatId in requestIds) return@mapNotNull null
            val record = repo.message(hit.messageId) ?: return@mapNotNull null
            // A group message from a blocked sender is stored but never found by search.
            if (ChatID.isGroup(record.chatId) && record.senderAccount in blockedNow) return@mapNotNull null
            val text = when (val p = map(listOf(record)).first().preview) {
                is MessagePreview.Text -> p.text
                is MessagePreview.Resource -> ""
            }
            MessageHit(hit.chatId, hit.messageId, text, Instant.ofEpochMilli(record.sentAt))
        }
    }

    // Sending

    override fun send(content: MessageContent, chatId: String, replyTo: String?) {
        val body = content as? MessageContent.Text ?: return
        val text = TextLimit.truncate(body.text)
        if (text.isBlank()) return
        // A preview a receiver would drop (link not in the text, too long, not a JPEG) is left off; the text still goes.
        val card = body.preview?.let { LinkPreviewRule.cardFor(text, it) }
        write {
            if (!sendableChat(chatId)) return@write
            outbox.enqueue(
                ContentEnvelope.Text(
                    freshId(), nowMs(), text, replyTo, ttl = ttlFor(chatId),
                    lp = card?.let { LinkPreview(it.url, it.title, it.desc, it.image) },
                ),
                chatId,
            )
            bumpActivity(chatId, nowMs())
            requestFlush()
        }
    }

    /**
     * Like [send] for text, for a caller that must know the message was queued (a notification reply). Returns the new
     * message's id once it is in the outbox, and null only when nothing was queued and nothing ever will be.
     *
     * The caller and the writer share one claim. The writer takes it before it enqueues; the caller takes it when it
     * gives up waiting ([waitMs]). Whoever takes it first decides: if the caller does, the writer finds it taken and
     * enqueues nothing, so "not queued" is true for good; if the writer already did, the enqueue is under way and the
     * caller waits for its id, so "queued" is never reported as null.
     */
    suspend fun sendReturningId(text: String, chatId: String, replyTo: String? = null, waitMs: Long = WRITE_WAIT_MS): String? {
        if (closed.get()) return null
        val body = TextLimit.truncate(text)
        if (body.isBlank()) return null
        val queued = kotlinx.coroutines.CompletableDeferred<String?>()
        val claimed = AtomicBoolean(false)
        write {
            try {
                beforeClaimForTest()
                if (!claimed.compareAndSet(false, true)) return@write // the caller gave up: enqueue nothing
                afterClaimForTest()
                if (!sendableChat(chatId)) return@write
                val id = freshId()
                outbox.enqueue(ContentEnvelope.Text(id, nowMs(), body, replyTo, ttl = ttlFor(chatId)), chatId)
                // From here on the message is queued, whatever else fails: the caller must not send it again.
                queued.complete(id)
                bumpActivity(chatId, nowMs())
                requestFlush()
            } finally {
                queued.complete(null) // does nothing once the id is out
            }
        }
        return kotlinx.coroutines.withTimeoutOrNull(waitMs) { queued.await() }
            ?: if (claimed.compareAndSet(false, true)) null else queued.await()
    }

    /** Test seams for [sendReturningId]: run in the writer before and after it claims the message. */
    internal var beforeClaimForTest: () -> Unit = {}
    internal var afterClaimForTest: () -> Unit = {}

    /** The chat's timer as the `ttl` of a new message; null when it is off. */
    private fun ttlFor(chatId: String): Int? = repo.timerSeconds(chatId).takeIf { it > 0 }

    private fun ttlOf(envelope: ByteArray?): Int? = when (val e = envelope?.let { ContentCodec.decode(it) }) {
        is ContentEnvelope.Photo -> e.ttl
        is ContentEnvelope.Voice -> e.ttl
        is ContentEnvelope.File -> e.ttl
        else -> null
    }

    private fun captionOf(envelope: ByteArray?): String? = when (val e = envelope?.let { ContentCodec.decode(it) }) {
        is ContentEnvelope.Photo -> e.c
        is ContentEnvelope.File -> e.c
        else -> null
    }

    /** The search text of an attachment's message: a file's name and the caption. */
    private fun foldedFor(name: String?, caption: String?): String = fold(listOfNotNull(name, caption).joinToString(" "))

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
    override fun sendAttachment(path: String, kind: MediaKind, chatId: String) = startAttachment(path, kind, chatId, null, null)

    override fun sendAttachment(path: String, kind: MediaKind, chatId: String, caption: String?) = startAttachment(path, kind, chatId, null, caption)

    override val attachmentLimitBytes: Long get() = maxAttachmentBytes

    /**
     * A preview's plan, in order: each attachment's message is stored (staged, `sending`) before the next one starts, so
     * the items keep their order in the chat. The caption is in the first one's message. The uploads then run side by side.
     */
    override fun sendBatch(sends: List<SendPlan.Send>, chatId: String) {
        if (closed.get() || sends.isEmpty()) return
        inFlight.update { it + 1 }
        scope.launch {
            try {
                for (item in sends) {
                    val created = CompletableDeferred<Unit>()
                    startAttachment(item.picked.path, item.picked.kind, chatId, created, item.caption)
                    created.await()
                }
            } finally {
                inFlight.update { it - 1 }
            }
        }
    }

    /** [created] completes once the message is stored (or the attachment was dropped), before its upload starts. */
    private fun startAttachment(path: String, kind: MediaKind, chatId: String, created: CompletableDeferred<Unit>?, rawCaption: String?) {
        // A caption follows the text rules: cut at the text limit, and none when blank. A voice message has none.
        val caption = rawCaption?.let { TextLimit.truncate(it) }?.takeIf { it.isNotBlank() && kind != MediaKind.Voice }
        if (closed.get()) { created?.complete(Unit); return }
        inFlight.update { it + 1 }
        scope.launch {
            val id = freshId()
            var stored = false
            kotlin.coroutines.coroutineContext[Job]?.let { uploadJobs[id] = it }
            try {
                if (!sendableChat(chatId)) return@launch
                uploading += id
                val staged = stage(id, path, chatId) ?: return@launch
                val at = nowMs()
                val ttl = ttlFor(chatId)
                val pending = PendingUpload(kind.name, chatId, staged.path)
                repo.transaction {
                    repo.insertMessage(
                        MessageRecord(
                            id = id, chatId = chatId, conversationId = null, senderAccount = device.accountId, senderDevice = device.deviceId,
                            sentAt = at, receivedAt = at,
                            envelope = ContentCodec.encode(envelopeFor(kind, id, at, placeholderAtt(staged.length()), PreparedMedia(staged), ttl, caption)),
                            status = "sending", replyTo = null, folded = caption?.let { fold(it) } ?: "",
                            expiresAt = Expiry.expiresAt(at, ttl?.toLong()), caption = caption,
                        ),
                    )
                    repo.set(PENDING_UPLOAD_PREFIX + id, Json.encodeToString(PendingUpload.serializer(), pending).encodeToByteArray())
                }
                stored = true
                bumpActivity(chatId, at)
                reload()
                created?.complete(Unit)
                upload(id, at, pending)
            } catch (e: CancellationException) {
                if (id in cancelled) withContext(NonCancellable) { removeUnsent(id) }
                throw e
            } catch (_: Exception) {
                if (stored) failUpload(id)
            } finally {
                if (!stored) pendingDir(id).deleteRecursively()
                uploading -= id
                uploadJobs -= id
                cancelled -= id
                progress.update { it - id }
                created?.complete(Unit)
                reload()
                inFlight.update { it - 1 }
            }
        }
    }

    /** The file as it is kept for this message: null when there is nothing to send. */
    private suspend fun stage(id: String, path: String, chatId: String): File? {
        if (path.startsWith("content:") || path.startsWith("file:")) {
            val dir = pendingDir(id)
            dir.mkdirs()
            val result = try {
                stageSource(path, dir)
            } catch (e: CancellationException) {
                dir.deleteRecursively()
                throw e
            } catch (_: Exception) {
                StageResult.Unreadable
            }
            val copy = (result as? StageResult.Copied)?.file?.takeIf { it.isFile }
            if (copy == null) {
                dir.deleteRecursively()
                raise(chatId, if (result is StageResult.TooBig) SendProblem.Reason.TooBig else SendProblem.Reason.Unreadable)
            }
            return copy
        }
        val file = File(path)
        if (!file.isFile) {
            raise(chatId, SendProblem.Reason.Unreadable)
            return null
        }
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
            kotlin.coroutines.coroutineContext[Job]?.let { uploadJobs[messageId] = it }
            try {
                reload()
                upload(messageId, row.sentAt, pending)
            } catch (e: CancellationException) {
                if (messageId in cancelled) withContext(NonCancellable) { removeUnsent(messageId) }
                throw e
            } catch (_: Exception) {
                failUpload(messageId)
            } finally {
                uploading -= messageId
                uploadJobs -= messageId
                cancelled -= messageId
                progress.update { it - messageId }
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
        // The timer chosen when the message was made stays with it, through a retry or a restart.
        val ttl = ttlOf(repo.message(id)?.envelope)
        val caption = captionOf(repo.message(id)?.envelope)
        var pending = start
        val keptFile = pending.prepared.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }
        val prepared: PreparedMedia = if (keptFile != null) {
            preparedFrom(repo.message(id)?.envelope, keptFile)
        } else {
            // A null answer means the file can never be sent (it cannot be decoded). A throw is an I/O error: when the kept
            // source is still there it may work next time, so the message stays failed (the caller marks it); when the
            // source is gone there is nothing to retry from.
            val fresh = try {
                mediaPreparer.prepare(pending.path, kind)?.takeIf { it.file.isFile }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (File(pending.path).isFile) throw e
                null
            }
            if (!currentCoroutineContext().isActive) {
                // Cancelled while it was being prepared (a blocking step that cannot stop half way): what it made goes too,
                // and an empty result is not "unreadable".
                if (fresh != null && (isInsideTemp(fresh.file) || isInside(fresh.file, pendingDir(id)))) fresh.file.delete()
                throw CancellationException("cancelled while preparing")
            }
            if (fresh == null) {
                // Nothing to retry from: it can never send, so it is removed instead of offering a dead "Try again".
                dropUnsendable(id, pending.chatId)
                return
            }
            val file = if (isInside(fresh.file, pendingDir(id))) fresh.file else if (isInsideTemp(fresh.file)) moveInto(pendingDir(id), fresh.file) else fresh.file
            val p = PreparedMedia(file, fresh.width, fresh.height, fresh.thumbnail, fresh.duration, fresh.name)
            pending = pending.copy(prepared = file.path)
            repo.transaction {
                repo.replaceUnsentEnvelope(id, device.accountId, ContentCodec.encode(envelopeFor(kind, id, at, placeholderAtt(file.length()), p, ttl, caption)), foldedFor(null, caption), nowMs())
                repo.set(PENDING_UPLOAD_PREFIX + id, Json.encodeToString(PendingUpload.serializer(), pending).encodeToByteArray())
            }
            reload()
            p
        }
        // The ciphertext waits in the temp folder until the message is stored, so no blob is ever left without a record.
        val uploaded = try {
            uploader.uploadKeeping(prepared.file) { setProgress(id, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failUpload(id)
            return
        }
        val att = uploaded.att
        val queued = try {
            repo.transaction {
                // The caption is read again from the row now: an edit made while the upload ran is the one that is sent.
                val row = repo.message(id)
                val finalCaption = if (row != null) captionOf(row.envelope) else caption
                val envelope = envelopeFor(kind, id, at, att, prepared, ttl, finalCaption)
                // False when the message was deleted while it uploaded: then nothing is queued and no record is written.
                if (!repo.replaceUnsentEnvelope(id, device.accountId, ContentCodec.encode(envelope), foldedFor(prepared.name.takeIf { kind == MediaKind.File || kind == MediaKind.Video }, finalCaption), nowMs())) {
                    false
                } else {
                    outbox.enqueue(envelope, pending.chatId)
                    // The sender's own copy: the same record a received file gets, LOCAL at the ciphertext the upload kept.
                    repo.upsertAttachment(
                        AttachmentRecord(att.id, id, attachmentKind(kind), att.key, att.dg, att.sz, att.csz, AttachmentState.LOCAL, att.csz, Uploader.blobFile(blobDir, att).path),
                    )
                    // The blob goes into place before the commit: if it cannot, nothing is queued and the send fails with its files kept.
                    uploaded.moveTo(blobDir)
                    repo.set(PENDING_UPLOAD_PREFIX + id, ByteArray(0))
                    true
                }
            }
        } catch (e: Throwable) {
            uploaded.discard()
            // A blob already moved (or half copied) before the failure has no record: it goes.
            try { Uploader.blobFile(blobDir, att).delete() } catch (_: Exception) { }
            throw e
        }
        if (!queued) {
            uploaded.discard()
            discardPending(id, prepared.file, pending.path)
            reload()
            return
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
    private fun dropUnsendable(id: String, chatId: String) {
        try {
            repo.transaction {
                repo.deleteUnsentMessage(id, device.accountId)
                repo.set(PENDING_UPLOAD_PREFIX + id, ByteArray(0))
            }
        } catch (_: Exception) {
            failUpload(id)
        }
        pendingDir(id).deleteRecursively()
        raise(chatId, SendProblem.Reason.Unreadable)
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

    /** True for an attachment envelope that still holds the placeholder: nothing about it was ever queued. */
    private fun isPlaceholder(envelope: ByteArray): Boolean = when (val e = runCatching { ContentCodec.decode(envelope) }.getOrNull()) {
        is ContentEnvelope.Photo -> e.a.id.isEmpty()
        is ContentEnvelope.Voice -> e.a.id.isEmpty()
        is ContentEnvelope.File -> e.a.id.isEmpty()
        else -> false
    }

    /** The stand-in until the upload gives the real one: no id, but the real size, so the bubble never shows 0 B. */
    private fun placeholderAtt(size: Long) = Att("", ByteArray(0), ByteArray(0), size, size)

    private fun envelopeFor(kind: MediaKind, id: String, at: Long, att: Att, p: PreparedMedia, ttl: Int? = null, caption: String? = null): ContentEnvelope = when (kind) {
        MediaKind.Photo -> ContentEnvelope.Photo(id, at, att, p.width, p.height, p.thumbnail, ttl = ttl, c = caption)
        // A video is file content (the same on every platform): its name keeps the extension and the mime says video/.
        MediaKind.Video -> ContentEnvelope.File(id, at, att, p.name, p.mime.ifEmpty { ChatMedia.videoMime(p.name) }, ttl = ttl, c = caption)
        MediaKind.Voice -> ContentEnvelope.Voice(id, at, att, p.duration, ttl = ttl)
        MediaKind.File -> ContentEnvelope.File(id, at, att, p.name, p.mime.ifEmpty { "application/octet-stream" }, ttl = ttl, c = caption)
    }

    /** What a retry knows about the prepared file: its length, size or name, from the stored envelope. */
    private fun preparedFrom(envelope: ByteArray?, file: File): PreparedMedia = when (val e = envelope?.let { ContentCodec.decode(it) }) {
        is ContentEnvelope.Photo -> PreparedMedia(file, e.w, e.h, e.th)
        is ContentEnvelope.Voice -> PreparedMedia(file, duration = e.dur)
        is ContentEnvelope.File -> PreparedMedia(file, name = e.name, mime = e.mime)
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
            val row = repo.message(id)
            if (row == null || row.deleted) {
                // Nobody can send or retry it: the staged file, the prepared copy, the folder and the key all go.
                val pending = runCatching { Json.decodeFromString(PendingUpload.serializer(), value.decodeToString()) }.getOrNull()
                discardPending(id, pending?.prepared?.takeIf { it.isNotEmpty() }?.let(::File), pending?.path)
            } else if (row.status == "sending") {
                repo.updateStatus(listOf(id), "failed")
            }
        }
        File(blobDir.parentFile ?: blobDir, "pending").listFiles()?.forEach { dir ->
            if (dir.name !in uploading && repo.get(PENDING_UPLOAD_PREFIX + dir.name)?.isNotEmpty() != true) dir.deleteRecursively()
        }
    }

    /**
     * Removes finished blobs (`<id>-<digest>.enc`) in the blob folder that nothing points to: a process killed after the
     * ciphertext was moved in but before the message was stored leaves one. Kept: a blob an attachment record points to,
     * one a pending-upload key mentions, and anything younger than [ORPHAN_BLOB_AGE_MS], so a send in progress in this
     * process is never touched.
     */
    private fun sweepOrphanBlobs() {
        val files = blobDir.listFiles { f -> f.isFile && f.name.endsWith(".enc") } ?: return
        val now = System.currentTimeMillis()
        val pendingText = repo.entries(PENDING_UPLOAD_PREFIX).values.filter { it.isNotEmpty() }.joinToString("\n") { it.decodeToString() }
        for (file in files) {
            if (uploading.isNotEmpty() || now - file.lastModified() < ORPHAN_BLOB_AGE_MS) continue
            val id = file.name.removeSuffix(".enc").substringBeforeLast('-')
            val record = try { repo.attachment(id) } catch (_: Exception) { continue }
            if (record != null || pendingText.contains(file.name) || pendingText.contains(file.path)) continue
            file.delete()
        }
    }

    private fun attachmentKind(kind: MediaKind) = when (kind) {
        MediaKind.Photo -> "photo"
        MediaKind.Voice -> "voice"
        MediaKind.File, MediaKind.Video -> "file"
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
        groupCreator.resumeInit()
        requestFlush()
        reload()
    }

    /** Removes decrypted files and partial downloads a killed process left: `Downloader.sweepOpen`, then `MediaCache.sweep`. */
    fun sweepStartup() {
        failInterruptedUploads()
        sweepOrphanBlobs()
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
        // Reading the row, applying the edit and looking at the pending-upload marker are ONE transaction, so an upload
        // cannot commit between them unseen: either the edit lands first and the upload carries the edited caption, or
        // the upload has committed (its marker is gone) and the Edit control goes behind the message in the queue.
        class Outcome(val isText: Boolean, val uploadStillPending: Boolean, val wire: String)
        val outcome = repo.transaction {
            val record = repo.message(messageId) ?: return@transaction null
            if (record.chatId != chatId || record.senderAccount != device.accountId || record.deleted) return@transaction null
            val old = ContentCodec.decode(record.envelope)
            val isText = old is ContentEnvelope.Text
            // A text message cannot be edited to nothing; an attachment's caption can (that removes it).
            if (isText && newText.isBlank()) return@transaction null
            val result = EditRule.apply(old, newText, fold) ?: return@transaction null
            if (!repo.applyEdit(messageId, device.accountId, ContentCodec.encode(result.envelope), result.folded, nowMs(), result.caption, result.linkPreview)) return@transaction null
            Outcome(isText, repo.get(PENDING_UPLOAD_PREFIX + messageId)?.isNotEmpty() == true, if (isText) newText else result.caption.orEmpty())
        } ?: return@write
        // While the upload has not committed, it reads the edited caption from this row and carries it itself.
        if (outcome.isText || !outcome.uploadStillPending) sendControl(chatId) { id, at -> ContentEnvelope.Edit(id, at, messageId, outcome.wire) }
    }

    override fun delete(messageId: String, chatId: String) = write {
        // Whether it was ever queued and the deletion are decided in one transaction, so a delete racing the upload's
        // commit (which only writes to a row that is not deleted) sends the Delete control if and only if the message went out.
        val queued: Boolean = repo.transaction {
            val record = repo.message(messageId) ?: return@transaction null
            if (record.chatId != chatId || record.senderAccount != device.accountId || record.deleted || record.id.startsWith(ChatMapping.CALL_PREFIX)) return@transaction null
            val left = !isPlaceholder(record.envelope)
            if (!repo.markDeleted(messageId, device.accountId)) null else left
        } ?: return@write
        // An attachment that never uploaded keeps nothing; one that is uploading cleans up when it finishes.
        if (messageId !in uploading && repo.get(PENDING_UPLOAD_PREFIX + messageId)?.isNotEmpty() == true) discardPending(messageId)
        // A message that never left the device has nothing to delete on the other side.
        if (queued) sendControl(chatId) { id, at -> ContentEnvelope.Delete(id, at, messageId) }
    }

    private fun sendControl(chatId: String, make: (String, Long) -> ContentEnvelope) {
        // A group I was removed from or left takes no control envelope either.
        if (isReadOnlyGroup(chatId)) return
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
        // A group chat ID is not an account: blocking it would delete the group and send a bogus ID to the server.
        if (ChatID.isGroup(contactId)) return@write
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
        if (ChatID.isGroup(contactId)) return@write
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
        if (user.accountId == device.accountId) return null // never add yourself
        val row = ContactRecord(user.accountId, user.username, user.displayName, true, contactRows[user.accountId]?.addedAt ?: nowMs())
        repo.upsertContact(row)
        reload()
        return contactOf(row)
    }

    override suspend fun addKnownContact(accountId: String, username: String, displayName: String): Contact? {
        val user = username.trim().lowercase(Locale.ROOT)
        if (accountId.isBlank() || user.isEmpty() || accountId == device.accountId) return null // never add yourself
        val row = ContactRecord(accountId, user, displayName, true, contactRows[accountId]?.addedAt ?: nowMs())
        repo.upsertContact(row)
        reload()
        return contactOf(row)
    }

    private val groupSync = org.example.app.messaging.sync.GroupSync(device, mls, server, repo, lock, clock, conversations, outbox, syncOnce = { inbox.sync() })
    private val groupAdmin = org.example.app.messaging.sync.GroupAdmin(device, server, repo, storeClock, groupSync, outbox)

    init {
        // A `members` item is only a hint: it is stored durably, then the group's list is read from the server.
        inbox.onMembers = { event -> if (groupSync.note(event)) scheduleGroupSync() }
    }

    private fun scheduleGroupSync() {
        if (closed.get()) return
        inFlight.update { it + 1 }
        scope.launch {
            try { runGroupSync() } finally { inFlight.update { it - 1 } }
        }
    }

    private suspend fun runGroupSync() {
        try { groupSync.runPending() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        requestFlush()
        reload()
    }

    /**
     * Reads the member list of every group this account is in, and follows up (devices of people who left are removed
     * from the group, devices of people just added are added). Call it at start-up and at every reconnect.
     */
    suspend fun syncGroups() {
        if (closed.get()) return
        groupSync.noteAll()
        runGroupSync()
    }

    private suspend fun <T> groupChange(block: suspend () -> T): T {
        if (closed.get()) throw GroupChangeFailed(GroupChangeFailed.Reason.Other)
        try {
            return block().also { requestFlush(); reload() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GroupChangeFailed) {
            throw e
        } catch (e: Exception) {
            throw GroupChangeFailed(GroupChangeFailed.Reason.Other, e)
        }
    }

    override suspend fun addMembers(chatId: String, accounts: List<String>): List<String> = groupChange { groupAdmin.addMembers(chatId, accounts) }
    override suspend fun removeMember(chatId: String, account: String) = groupChange { groupAdmin.removeMember(chatId, account) }
    override suspend fun leaveGroup(chatId: String) = groupChange { groupAdmin.leave(chatId) }
    override suspend fun setRole(chatId: String, account: String, role: org.example.app.core.groups.GroupRole) =
        groupChange { groupAdmin.setRole(chatId, account, role) }
    override suspend fun renameGroup(chatId: String, name: String) = groupChange { groupAdmin.rename(chatId, name) }
    override suspend fun setGroupPhoto(chatId: String, photo: ByteArray?) = groupChange { groupAdmin.setPhoto(chatId, photo) }

    override suspend fun createGroup(contactIds: List<String>, name: String, photo: ByteArray?): GroupCreated {
        if (closed.get()) throw GroupCreateFailed(GroupCreateFailed.Reason.Other)
        try {
            val made = groupCreator.create(contactIds, name, photo)
            reload()
            requestFlush()
            return GroupCreated(made.chatId, made.notAdded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: org.example.app.contracts.ServerException) {
            throw GroupCreateFailed(
                when (e.code) {
                    "not_found" -> GroupCreateFailed.Reason.NotFound
                    "group_full" -> GroupCreateFailed.Reason.Full
                    else -> GroupCreateFailed.Reason.Other
                },
                e,
            )
        } catch (e: org.example.app.messaging.sync.SyncException.NoKeyPackages) {
            throw GroupCreateFailed(GroupCreateFailed.Reason.NobodyReachable, e)
        } catch (e: Exception) {
            throw GroupCreateFailed(GroupCreateFailed.Reason.Other, e)
        }
    }

    override fun ensureChat(contactId: String): String {
        ensureChatRow(contactId)
        reload()
        return contactId
    }

    /** A group chat I am no longer a member of (removed or left): it shows its history and takes nothing new. */
    private fun isReadOnlyGroup(chatId: String): Boolean {
        if (!ChatID.isGroup(chatId)) return false
        val row = repo.chat(chatId) ?: return true
        return row.kind == ChatKind.GROUP && row.myState != MyState.MEMBER
    }

    /** Whether a message may be queued to [chatId]: a one-to-one chat is made on demand; a group must exist and still have me in it. */
    private fun sendableChat(chatId: String): Boolean =
        if (ChatID.isGroup(chatId)) repo.chat(chatId)?.kind == ChatKind.GROUP && !isReadOnlyGroup(chatId) else ensureChatRow(chatId)

    /** False for a group chat ID: it is no contact, so no contact row and no one-to-one chat is ever made for it. */
    private fun ensureChatRow(contactId: String): Boolean = if (ChatID.isGroup(contactId)) false else try {
        if (repo.chat(contactId) == null) {
            val known = repo.contact(contactId)
            if (known == null) repo.upsertContact(ContactRecord(contactId, "", "", false, nowMs()))
            repo.upsertChat(ChatRecord(contactId, isRequest = known?.isContact != true, unread = 0, muted = false, lastActivity = nowMs()))
        }
        true
    } catch (_: Exception) {
        false
    }

    /** One column in one statement: a pin, mute or archive made meanwhile is never written back to its old value. */
    private fun bumpActivity(chatId: String, at: Long) = repo.bumpActivity(chatId, at)

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

    // People base

    override fun timerSeconds(chatId: String): Int = repo.timerSeconds(chatId)

    override fun setTimer(chatId: String, seconds: Int) {
        if (org.example.app.core.people.TimerChoice.from(seconds) == null) return
        write {
            val queued = repo.transaction {
                if (repo.chat(chatId) == null || repo.timerSeconds(chatId) == seconds) return@transaction false
                if (isReadOnlyGroup(chatId)) return@transaction false
                // In a group only an owner or admin sets it (the others would be ignored by every member).
                if (ChatID.isGroup(chatId)) {
                    val mine = repo.groupMembers(chatId).firstOrNull { it.accountId == device.accountId }?.let { org.example.app.core.groups.GroupRole.from(it.role) }
                    if (mine?.canSetTimer != true) return@transaction false
                }
                // The id is made here, and is the id on the wire: it breaks a tie with the peer's timer at the same millisecond.
                val id = freshId()
                var at = 0L
                // One `timer` control through the normal send path, and the same line the peer will show, without a name.
                outbox.enqueueControl(chatId) { _, now -> at = now; ContentEnvelope.Timer(id, now, seconds) }
                repo.setTimer(chatId, seconds)
                // Remembered with the time and id of the choice, so an older (or tied but lower) timer from the peer cannot override it.
                repo.set("timer-at:$chatId", at.toString().encodeToByteArray())
                repo.set("timer-id:$chatId", id.encodeToByteArray())
                repo.insertMessage(
                    MessageRecord(
                        id = "sys-timer-$id", chatId = chatId, conversationId = null, senderAccount = ChatMapping.SYSTEM_SENDER,
                        senderDevice = null, sentAt = at, receivedAt = at, envelope = "{\"timer\":{\"seconds\":$seconds}}".encodeToByteArray(),
                        status = "sent",
                    ),
                )
                true
            }
            if (queued) requestFlush()
        }
    }

    override fun sweepExpired(now: Long): List<String> {
        if (closed.get()) return emptyList()
        // The attachments of what is about to go, to remove their decrypted copies afterwards.
        val openNames = repo.expiredMessageIds(now).mapNotNull { repo.attachmentForMessage(it) }.map { Downloader.openName(it.id, it.digest, it.key) }
        val removed = repo.sweepExpired(now, device.accountId)
        // The decrypted copy in the open folder, by the exact name `Downloader.open` gave it.
        for (name in openNames) File(dirs.openDir, name).delete()
        // A failed or waiting upload that expired leaves nothing behind: its staged files and its pending key.
        for (id in removed) {
            if (pendingDir(id).exists() || repo.get(PENDING_UPLOAD_PREFIX + id)?.isNotEmpty() == true) discardPending(id)
        }
        if (removed.isNotEmpty()) reload()
        return removed
    }

    override fun profile(accountId: String): org.example.app.core.people.Profile? =
        repo.profile(accountId)?.let { org.example.app.core.people.Profile(it.accountId, it.name, it.photo, it.ver) }

    override fun saveProfile(accountId: String, name: String, photo: ByteArray?, ver: Int): Boolean {
        // The same rules as the wire: a name that fails the display-name rule, or an oversized photo, is not kept.
        if (org.example.app.core.profile.DisplayNameRule.clean(name) != name) return false
        if (photo != null && !org.example.app.core.people.ProfilePhotoRule.isAcceptable(photo)) return false
        val saved = repo.saveProfile(accountId, name, photo, ver)
        if (saved) reload()
        return saved
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
        private const val ORPHAN_BLOB_AGE_MS = 10 * 60 * 1000L
        private const val WRITE_WAIT_MS = 10_000L
        private const val REPORTED_MESSAGES = 10
        const val BLOCK_PREFIX = "pending.block."
        const val UNBLOCK_PREFIX = "pending.unblock."
        const val PENDING_REPORT_PREFIX = "pending.report."
        private const val PENDING_UPLOAD_PREFIX = "pending.upload."
        private val NO_ATT = Att("", ByteArray(0), ByteArray(0), 0, 0)
        private const val REPORTED_PREFIX = "reported."
    }
}
