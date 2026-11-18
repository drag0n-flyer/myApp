package org.example.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.Chat
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.ChatTimeline
import org.example.app.core.chats.Contact
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.HistoryPager
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageAction
import org.example.app.core.chats.MessageActions
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.MessageHit
import org.example.app.core.chats.Picked
import org.example.app.core.chats.LinkPreviewCard
import org.example.app.core.chats.LinkPreviewRule
import org.example.app.core.chats.SendPlan
import org.example.app.core.chats.SendProblem
import org.example.app.ui.media.ChatMediaIndex
import org.example.app.ui.media.ViewerItem
import org.example.app.core.settings.AutoDownloadPolicy
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.core.settings.MediaKind
import org.example.app.messaging.content.TextLimit

data class ChatState(
    val chat: Chat?,
    val contactName: String,
    /** The cached newest messages and any older pages loaded since, oldest first, one copy per id. */
    val messages: List<Message>,
    val sendingPaused: Boolean,
    /** A message request: the screen shows Accept / Block / Report in place of the composer. */
    val isRequest: Boolean = false,
)

class ChatViewModel(
    private val store: ChatStore,
    val chatId: String,
    private val network: NetworkType,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Typing and presence; null shows and sends none. */
    private val signals: org.example.app.core.people.PeopleSignals? = null,
    private val autoDownload: () -> AutoDownloadSettings,
) : ViewModel() {
    // Every message this screen has seen: the store's cached newest page as it moves on, plus older pages loaded since.
    // Messages that fall out of the store's cache while the chat is open stay on screen. The store's copy always wins.
    private val known = MutableStateFlow(store.chat(chatId)?.messages.orEmpty())
    private val loadLock = Mutex()
    private val searchLock = Mutex()
    private val pager = HistoryPager()
    private var refreshJob: Job? = null
    private var refreshAgain = false
    private var refreshOldest: String? = null

    // Only this chat matters: other chats changing must not recompute anything.
    private val chatFlow = combine(store.chats, store.requests) { chats, requests ->
        chats.firstOrNull { it.id == chatId } ?: requests.firstOrNull { it.id == chatId }
    }.distinctUntilChanged()

    val state: StateFlow<ChatState> =
        combine(chatFlow, known) { chat, pages -> read(chat, pages) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, read(store.chat(chatId), emptyList()))

    /** The first message that was unread when the chat opened; the "Unread messages" line stays above it until the chat closes. */
    val firstUnreadId: String?

    init {
        val chat = store.chat(chatId)
        firstUnreadId = chat?.let { ChatTimeline.firstUnreadId(it.messages, it.unreadCount, store.me.id) }
        store.markRead(chatId)
        viewModelScope.launch {
            var seen: List<Message>? = null
            chatFlow.collect { chat ->
                if (chat != null) {
                    known.update { ChatTimeline.merge(withoutRemoved(it, chat.messages), chat.messages) }
                    // Only a change in this chat's messages is worth re-reading the older range for.
                    if (chat.messages != seen) requestRefresh(chat.messages.firstOrNull()?.id)
                    seen = chat.messages
                    if (atBottom) trimOlder()
                }
            }
        }
    }

    /**
     * A message this screen has seen that is newer than the oldest message in the store's cached page, yet no longer in it,
     * was removed (a cancelled upload): it must not stay on screen. Older ones merely fell out of the cache and are kept.
     */
    private fun withoutRemoved(seen: List<Message>, cache: List<Message>): List<Message> {
        val oldest = cache.firstOrNull()?.sentAt
        val cacheIds = cache.mapTo(HashSet()) { it.id }
        return seen.filter { it.id in cacheIds || (oldest != null && it.sentAt <= oldest) }
    }

    private fun read(chat: Chat?, pages: List<Message>): ChatState = ChatState(
        chat = chat,
        contactName = chat?.let { store.contact(it.contactId)?.displayName } ?: "",
        messages = if (pages.isEmpty()) chat?.messages.orEmpty() else ChatTimeline.merge(pages, chat?.messages.orEmpty()),
        sendingPaused = chat?.modeMismatch == true,
        isRequest = chat?.isRequest == true,
    )

    private val myId: String get() = store.me.id

    /** What the header shows under the name: typing, online or last seen. Nothing for a message request. */
    val peer: StateFlow<org.example.app.core.people.PeerView?> =
        (signals?.views ?: MutableStateFlow<Map<String, org.example.app.core.people.PeerView>>(emptyMap()))
            .map { views -> if (state.value.isRequest) null else views[chatId] }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The composer text changed: starts, repeats or ends the typing signal. A message request gets none. */
    fun typed(isEmpty: Boolean) {
        if (!state.value.isRequest) signals?.composerChanged(chatId, isEmpty)
    }

    /**
     * Read straight from the store and the loaded pages rather than from [state], which a collector updates a moment
     * later: decisions here must not depend on whether that has happened yet.
     */
    private fun snapshot(): List<Message> = ChatTimeline.merge(known.value, store.chat(chatId)?.messages.orEmpty())

    private fun current(messageId: String): Message? = snapshot().firstOrNull { it.id == messageId }

    private fun mine(m: Message) = m.senderId == myId

    /** Sends trimmed text, cut to the grapheme limit. Returns false when there was nothing to send. */
    fun send(text: String, replyTo: String? = null, preview: LinkPreviewCard? = null): Boolean {
        val clean = TextLimit.truncate(text).trim()
        if (clean.isEmpty() || state.value.sendingPaused) return false
        // A reply to a message that is gone or deleted would show a dead quote; send it as a plain message.
        val target = replyTo?.takeIf { id -> current(id)?.let { MessageActions.stillApplies(MessageAction.Reply, it, mine(it)) } == true }
<<<<<<< HEAD
        store.send(MessageContent.Text(clean, preview?.let { LinkPreviewRule.cardFor(clean, it) }), chatId, target)
=======
        store.send(MessageContent.Text(clean), chatId, target)
        signals?.messageSent(chatId)
>>>>>>> origin/main
        return true
    }

    /** The chat screen was left: a typing signal that is out ends at once. Sends nothing when none is out. */
    fun leftChat() { signals?.messageSent(chatId) }

    // Older history

    /**
     * Messages older than the store's cached page are copies from when they were loaded. After any change in the chat
     * (a reaction, edit or delete, ours or remote) read that range again so a deleted message does not keep its text.
     */

    /** At most one refresh runs and one more waits: a burst of changes costs two reads, not one per change. */
    private fun requestRefresh(cacheOldestId: String?) {
        refreshOldest = cacheOldestId
        if (refreshJob?.isActive == true) {
            refreshAgain = true
            return
        }
        refreshJob = viewModelScope.launch {
            do {
                refreshAgain = false
                refreshLoadedOlder(refreshOldest)
            } while (refreshAgain)
        }
    }

    private suspend fun refreshLoadedOlder(cacheOldestId: String?) {
        if (cacheOldestId == null) return
        loadLock.withLock {
            val count = known.value.indexOfFirst { it.id == cacheOldestId }
            if (count <= 0) return@withLock
            val fresh = withContext(io) { store.messages(chatId, cacheOldestId, minOf(count, MAX_REFRESH)) }
            // The fresh copies win over the stale ones.
            known.update { ChatTimeline.merge(it, fresh) }
        }
    }


    /** The screen's oldest visible row, counted from the oldest loaded message; asks for a page when it is near the start. */
    fun maybeLoadOlder(oldestVisibleIndex: Int) {
        if (pager.shouldLoad(oldestVisibleIndex)) viewModelScope.launch { loadOlder() }
    }

    private var atBottom = true

    // Set from the moment a jump starts loading pages until the screen reports it has scrolled or given up. The list is
    // still at the bottom while the pages load, so "at the bottom" alone would let a store change trim the target away.
    private var jumps = 0

    /** The screen reports that a jump has scrolled to its target, or gave up (also if it left composition mid-jump). */
    fun finishJump() {
        if (jumps > 0) jumps--
    }

    /**
     * The screen reports whether the newest message is on screen. Loaded history is trimmed only then (see [trimOlder]),
     * and once when the reader gets back to the bottom.
     */
    fun setAtBottom(value: Boolean) {
        val changed = value && !atBottom
        atBottom = value
        if (changed) viewModelScope.launch { trimOlder() }
    }

    /**
     * Above [MAX_OLDER] loaded older messages, keep the newest [KEEP_OLDER] and let the pager ask for the trimmed ones
     * again. Only called while the newest message is on screen: trimming under a reader who scrolls up, or who just
     * jumped to an old message, would drop what they are looking at.
     */
    private suspend fun trimOlder() = loadLock.withLock {
        if (jumps > 0) return@withLock
        val cacheIds = store.chat(chatId)?.messages.orEmpty().map { it.id }.toSet()
        val older = known.value.filter { it.id !in cacheIds }
        if (older.size <= MAX_OLDER) return@withLock
        val keep = older.takeLast(KEEP_OLDER).map { it.id }.toSet()
        known.update { all -> all.filter { it.id in cacheIds || it.id in keep } }
        pager.reset()
    }

    /**
     * Loads one older page. False when history has ended (nothing more can be loaded). Safe to call from several places.
     */
    suspend fun loadOlder(): Boolean = loadLock.withLock {
        if (!pager.begin()) return@withLock false
        try {
            val before = snapshot()
            val page = withContext(io) { store.messages(chatId, before.firstOrNull()?.id, HistoryPager.PAGE_SIZE) }
            known.update { ChatTimeline.merge(it, page) }
            // A page that adds nothing new is the end, however long it is: never loop on a store that repeats itself.
            val grew = snapshot().size > before.size
            pager.finish(if (grew) page.size else 0)
            true
        } catch (e: Exception) {
            pager.abort()
            throw e
        }
    }

    /** Loads older pages until [messageId] is loaded. False when history ended first; the loaded messages stay loaded. */
    suspend fun ensureLoaded(messageId: String): Boolean {
        // The caller must call [finishJump] when it has scrolled or given up.
        jumps++
        var pages = 0
        while (true) {
            if (current(messageId) != null) return true
            // A jump never loads more than this many pages; the message is then reported as not found.
            if (pages++ >= MAX_JUMP_PAGES || !loadOlder()) return current(messageId) != null
        }
    }

    // Actions. Each re-checks the message as it is now, because the menu was built from an earlier copy.

    private var lastReaction: Pair<String, Long>? = null

    /** False when the message is gone or cannot take a reaction, or when this is a second tap within half a second. */
    fun react(emoji: String, messageId: String): Boolean {
        val m = current(messageId) ?: return false
        if (!MessageActions.stillApplies(MessageAction.React, m, mine(m))) return false
        val now = nowMs()
        lastReaction?.let { (id, at) -> if (id == messageId && now - at < REACTION_REPEAT_MS) return false }
        lastReaction = messageId to now
        store.react(emoji, messageId, chatId)
        reloadAfterWrite(messageId)
        return true
    }

    /** True when the edit was applied or there was nothing to change; false when the message cannot be edited any more. */
    fun edit(messageId: String, text: String): Boolean {
        val m = current(messageId) ?: return false
        if (!MessageActions.stillApplies(MessageAction.Edit, m, mine(m))) return false
        val clean = TextLimit.truncate(text).trim()
        val attachment = m.content is MessageContent.Photo || m.content is MessageContent.File
        // A text message cannot be edited to nothing; an attachment's blank edit removes its caption.
        if (clean.isEmpty() && !attachment) return false
        val unchanged = when (val c = m.content) {
            is MessageContent.Text -> c.text == clean
            is MessageContent.Photo -> c.caption.orEmpty() == clean
            is MessageContent.File -> c.caption.orEmpty() == clean
            else -> false
        }
        if (unchanged) return true
        store.edit(messageId, chatId, clean)
        reloadAfterWrite(messageId)
        return true
    }

    fun delete(messageId: String): Boolean {
        val m = current(messageId) ?: return false
        if (!MessageActions.stillApplies(MessageAction.Delete, m, mine(m))) return false
        store.delete(messageId, chatId)
        reloadAfterWrite(messageId)
        return true
    }

    /**
     * A change to a message outside the store's cached page does not make the chat flow emit, so it would not show until
     * some other change. Once the store says its writes have landed, read that one message again and replace it.
     */
    private fun reloadAfterWrite(messageId: String) {
        viewModelScope.launch {
            store.awaitWrites()
            val loaded = known.value
            val index = loaded.indexOfFirst { it.id == messageId }
            if (index < 0) return@launch
            if (store.chat(chatId)?.messages.orEmpty().any { it.id == messageId }) return@launch // the flow carries it
            // The message just before the next newer one is this message, unless hidden lines sit in between: read a few.
            val next = loaded.getOrNull(index + 1) ?: return@launch
            val page = withContext(io) { store.messages(chatId, next.id, REREAD_PAGE) }
            page.firstOrNull { it.id == messageId }?.let { fresh -> known.update { ChatTimeline.merge(it, listOf(fresh)) } }
        }
    }

    /** "Try again" on a message that was not sent. */
    fun retry(messageId: String): Boolean {
        val m = current(messageId) ?: return false
        if (!mine(m) || m.status != DeliveryStatus.Failed) return false
        store.retrySend(messageId)
        return true
    }

    /** People a text can be forwarded to. */
    fun forwardTargets(): List<Contact> = store.contacts.value.filter { it.id != myId && it.id !in store.blockedIds.value }

    /** Sends [text] to the chat with [contactId], creating it if needed. Returns that chat's ID, or null when there was nothing to send. */
    fun forward(text: String, contactId: String): String? {
        val clean = TextLimit.truncate(text).trim()
        if (clean.isEmpty()) return null
        val target = store.ensureChat(contactId)
        store.send(MessageContent.Text(clean), target, null)
        return target
    }

    /** Results from this chat only, newest first as the store gives them. Reads the database: call it off the main thread. */
    suspend fun search(query: String): List<MessageHit> {
        if (query.isBlank()) return emptyList()
        // The store searches every chat, so a small limit can be filled by other chats' hits: widen it until this chat's
        // hits are enough or the store has nothing more.
        // One search at a time: a run that was abandoned for a newer query finishes its pass, then the next one starts.
        return searchLock.withLock {
            withContext(io) {
                var limit = SEARCH_LIMIT
                var mine: List<MessageHit>
                var done: Boolean
                do {
                    ensureActive()
                    val all = store.searchMessages(query, limit)
                    mine = all.filter { it.chatId == chatId }
                    // A pass that returned fewer hits than it asked for has seen everything.
                    done = mine.size >= SEARCH_WANTED || all.size < limit || limit >= SEARCH_MAX
                    limit = minOf(limit * 5, SEARCH_MAX)
                } while (!done)
                mine
            }
        }
    }


    // Highlight from a search hit in the chat list: consumed once, so returning from chat info does not jump again.

    private var handledHighlight: String? = null

    fun takeHighlight(messageId: String): Boolean {
        if (handledHighlight == messageId) return false
        handledHighlight = messageId
        return true
    }

    /** The contact to block from the request bar, read fresh; null when the chat cannot be resolved any more. */
    fun blockTarget(): String? = store.chat(chatId)?.contactId

    // Drafts

    /**
     * The unsent text saved for this chat. It lives in the store, which belongs to the signed-in account: another
     * account never sees it, and it goes with the account's data on sign-out.
     */
    fun draft(): String = store.chat(chatId)?.draft.orEmpty()

    fun setDraft(text: String) = store.setDraft(chatId, text)

    // Media (unchanged by this task)

    // Lives in the ViewModel, so it survives rotation and a message scrolling off and back on screen.
    // The store reports no failure, so an id is never removed; the tap on the download button still retries.
    private val requestedDownloads = mutableSetOf<String>()

    /** Starts the automatic download for a message once, if the rules allow it on this network. */
    fun requestAutoDownload(messageId: String, kind: MediaKind) {
        if (autoDownloads(kind) && requestedDownloads.add(messageId)) store.download(messageId, chatId)
    }

    fun download(messageId: String) = store.download(messageId, chatId)

    /** Sends a picked, photographed or recorded file; [path] is a file the sender can read, or a `content:` URI for a photo. */
    fun sendAttachment(path: String, kind: MediaKind) = store.sendAttachment(path, kind, chatId)

    /**
     * Send on the preview: the items in order with the trimmed caption as a text message after the first, or the refusal
     * (more than ten, a video over the server's limit) with nothing staged or sent. A paused chat sends nothing.
     */
    fun sendPicked(items: List<Picked>, caption: String): SendPlan.Result {
        val result = SendPlan.plan(items, TextLimit.truncate(caption), store.attachmentLimitBytes)
        if (result is SendPlan.Result.Ok && !state.value.sendingPaused) store.sendBatch(result.sends, chatId)
        return result
    }

    /** The refusal [sendPicked] would give for [items] (checked when they are picked), or null. */
    fun refusalFor(items: List<Picked>): SendPlan.Refusal? = (SendPlan.plan(items, "", store.attachmentLimitBytes) as? SendPlan.Result.Refused)?.reason

    /** The fraction uploaded of each outgoing attachment that is still uploading, by message id. */
    val uploadProgress: StateFlow<Map<String, Double>> get() = store.uploadProgress

    /** Cancel on an outgoing attachment that is still uploading: it stops and the message is removed. */
    fun cancelUpload(messageId: String) = store.cancelUpload(messageId)

    /** The photos and videos on this device from the whole chat, oldest first: what the viewer swipes over. */
    suspend fun viewerItems(): List<ViewerItem> = withContext(io) { ChatMediaIndex.viewerItems(ChatMediaIndex.load(store, chatId)) }

    /** Why the last attachment sent from this chat was dropped (too big, unreadable), until it has been shown; null when none. */
    val sendProblem: kotlinx.coroutines.flow.Flow<SendProblem.Reason?> = store.sendProblem(chatId)

    /** The notice has been shown: the store forgets the problem, so it is shown once. */
    fun sendProblemShown() = store.takeSendProblem(chatId)

    /** Photos and files wait for a tap when the rules say not to download on this network. */
    fun autoDownloads(kind: MediaKind): Boolean = AutoDownloadPolicy.shouldDownload(kind, network, autoDownload())

    private companion object {
        const val REACTION_REPEAT_MS = 500L
        const val MAX_JUMP_PAGES = 40
        const val SEARCH_LIMIT = 200
        const val SEARCH_WANTED = 50
        const val SEARCH_MAX = 1_000
        const val MAX_REFRESH = 1_000
        const val MAX_OLDER = 1_000
        const val KEEP_OLDER = 500
        const val REREAD_PAGE = 3
    }
}
