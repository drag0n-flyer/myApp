package org.example.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
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
import org.example.app.core.chats.DraftKeeper
import org.example.app.core.chats.HistoryPager
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageAction
import org.example.app.core.chats.MessageActions
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.MessageHit
import org.example.app.core.settings.AutoDownloadPolicy
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.core.settings.MediaKind
import org.example.app.messaging.content.TextLimit

/** Unsent composer text, kept for the life of the process so leaving a chat and coming back keeps it. */
internal object ProcessDrafts {
    val keeper = DraftKeeper()
}

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
    private val drafts: DraftKeeper = ProcessDrafts.keeper,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val autoDownload: () -> AutoDownloadSettings,
) : ViewModel() {
    // Every message this screen has seen: the store's cached newest page as it moves on, plus older pages loaded since.
    // Messages that fall out of the store's cache while the chat is open stay on screen. The store's copy always wins.
    private val known = MutableStateFlow(store.chat(chatId)?.messages.orEmpty())

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
            chatFlow.collect { chat -> if (chat != null) known.update { ChatTimeline.merge(it, chat.messages) } }
        }
    }

    private fun read(chat: Chat?, pages: List<Message>): ChatState = ChatState(
        chat = chat,
        contactName = chat?.let { store.contact(it.contactId)?.displayName } ?: "",
        messages = if (pages.isEmpty()) chat?.messages.orEmpty() else ChatTimeline.merge(pages, chat?.messages.orEmpty()),
        sendingPaused = chat?.modeMismatch == true,
        isRequest = chat?.isRequest == true,
    )

    private val myId: String get() = store.me.id

    /**
     * Read straight from the store and the loaded pages rather than from [state], which a collector updates a moment
     * later: decisions here must not depend on whether that has happened yet.
     */
    private fun snapshot(): List<Message> = ChatTimeline.merge(known.value, store.chat(chatId)?.messages.orEmpty())

    private fun current(messageId: String): Message? = snapshot().firstOrNull { it.id == messageId }

    private fun mine(m: Message) = m.senderId == myId

    /** Sends trimmed text, cut to the grapheme limit. Returns false when there was nothing to send. */
    fun send(text: String, replyTo: String? = null): Boolean {
        val clean = TextLimit.truncate(text).trim()
        if (clean.isEmpty() || state.value.sendingPaused) return false
        // A reply to a message that is gone or deleted would show a dead quote; send it as a plain message.
        val target = replyTo?.takeIf { id -> current(id)?.let { MessageActions.stillApplies(MessageAction.Reply, it, mine(it)) } == true }
        store.send(MessageContent.Text(clean), chatId, target)
        return true
    }

    // Older history

    private val pager = HistoryPager()
    private val loadLock = Mutex()

    /** The screen's oldest visible row, counted from the oldest loaded message; asks for a page when it is near the start. */
    fun maybeLoadOlder(oldestVisibleIndex: Int) {
        if (pager.shouldLoad(oldestVisibleIndex)) viewModelScope.launch { loadOlder() }
    }

    /** Loads one older page. False when history has ended (nothing more can be loaded). Safe to call from several places. */
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
        while (true) {
            if (current(messageId) != null) return true
            if (!loadOlder()) return current(messageId) != null
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
        return true
    }

    /** True when the edit was applied or there was nothing to change; false when the message cannot be edited any more. */
    fun edit(messageId: String, text: String): Boolean {
        val m = current(messageId) ?: return false
        if (!MessageActions.stillApplies(MessageAction.Edit, m, mine(m))) return false
        val clean = TextLimit.truncate(text).trim()
        if (clean.isEmpty()) return false
        if ((m.content as? MessageContent.Text)?.text == clean) return true
        store.edit(messageId, chatId, clean)
        return true
    }

    fun delete(messageId: String): Boolean {
        val m = current(messageId) ?: return false
        if (!MessageActions.stillApplies(MessageAction.Delete, m, mine(m))) return false
        store.delete(messageId, chatId)
        return true
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
        return withContext(io) { store.searchMessages(query, SEARCH_LIMIT).filter { it.chatId == chatId } }
    }

    // Drafts

    fun draft(): String = drafts.draft(chatId)

    fun setDraft(text: String) = drafts.set(text, chatId)

    // Media (unchanged by this task)

    // Lives in the ViewModel, so it survives rotation and a message scrolling off and back on screen.
    // The store reports no failure, so an id is never removed; the tap on the download button still retries.
    private val requestedDownloads = mutableSetOf<String>()

    /** Starts the automatic download for a message once, if the rules allow it on this network. */
    fun requestAutoDownload(messageId: String, kind: MediaKind) {
        if (autoDownloads(kind) && requestedDownloads.add(messageId)) store.download(messageId, chatId)
    }

    fun download(messageId: String) = store.download(messageId, chatId)

    /** Photos and files wait for a tap when the rules say not to download on this network. */
    fun autoDownloads(kind: MediaKind): Boolean = AutoDownloadPolicy.shouldDownload(kind, network, autoDownload())

    private companion object {
        const val REACTION_REPEAT_MS = 500L
        const val SEARCH_LIMIT = 200
    }
}
