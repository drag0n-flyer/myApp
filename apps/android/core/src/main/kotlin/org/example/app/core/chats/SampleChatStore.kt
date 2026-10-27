package org.example.app.core.chats

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.example.app.core.settings.MediaKind
import java.time.Instant

/** In-memory chat store driven by `SampleData`. Safe to call from any thread. */
class SampleChatStore(
    data: SampleData = SampleData.default,
    private val clock: () -> Instant = Instant::now,
) : ChatStore {
    override val me: Contact = data.me

    private val lock = Any()
    private var allChats: List<Chat> = data.chats
    private val directory: Map<String, String> = data.directory
    private val requestSenders = data.requestSenders.associateBy { it.id }.toMutableMap()
    private val verifiedChatIds = mutableSetOf<String>()
    private var nextId = 0

    private val _contacts = MutableStateFlow(data.contacts)
    private val _folders = MutableStateFlow(data.folders)
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
    override val supportsSafetyNumbers: Boolean = true

    init {
        publish()
    }

    override fun chat(id: String): Chat? = synchronized(lock) { allChats.firstOrNull { it.id == id } }

    override fun contact(id: String): Contact? =
        _contacts.value.firstOrNull { it.id == id } ?: synchronized(lock) { requestSenders[id] }

    override fun messages(chatId: String, before: String?, limit: Int): List<Message> {
        val all = chat(chatId)?.messages ?: return emptyList()
        if (limit <= 0) return emptyList()
        var end = all.size
        if (before != null) {
            end = all.indexOfFirst { it.id == before }
            if (end < 0) return emptyList()
        }
        return all.subList(maxOf(0, end - limit), end)
    }

    override fun searchMessages(query: String, limit: Int): List<MessageHit> {
        val q = query.trim()
        if (q.isEmpty() || limit <= 0) return emptyList()
        val hits = mutableListOf<MessageHit>()
        for (chat in _chats.value) {
            for (message in chat.messages.asReversed()) {
                val content = message.content as? MessageContent.Text ?: continue
                if (!content.text.contains(q, ignoreCase = true)) continue
                hits += MessageHit(chat.id, message.id, content.text)
                if (hits.size == limit) return hits
            }
        }
        return hits
    }

    override fun send(content: MessageContent, chatId: String, replyTo: String?) = mutate {
        append(Message(makeId(), me.id, clock(), content, DeliveryStatus.Sent, replyTo, emptyList()), chatId)
    }

    override fun sendAttachment(path: String, kind: MediaKind, chatId: String) {
        val content = when (kind) {
            MediaKind.Photo, MediaKind.Video -> MessageContent.Photo(0)
            MediaKind.Voice -> MessageContent.Voice(0.0)
            MediaKind.File -> MessageContent.File(path.substringAfterLast('/'), 0)
        }
        send(content, chatId, null)
    }

    override fun react(emoji: String, messageId: String, chatId: String) = updateMessage(messageId, chatId) {
        if (emoji in it.reactions) it.copy(reactions = it.reactions - emoji) else it.copy(reactions = it.reactions + emoji)
    }

    override fun edit(messageId: String, chatId: String, text: String) = updateMessage(messageId, chatId) {
        if (it.senderId == me.id && it.content is MessageContent.Text) it.copy(content = MessageContent.Text(text), editedAt = clock()) else it
    }

    override fun delete(messageId: String, chatId: String) = updateMessage(messageId, chatId) {
        it.copy(content = MessageContent.Deleted, media = null, reactions = emptyList())
    }

    /** Sample data has nothing to download. */
    override fun download(messageId: String, chatId: String) {}

    override fun recordCall(summary: CallSummary, contactId: String, outgoing: Boolean, at: Instant) = mutate {
        val chatId = ensureChatLocked(contactId)
        val sender = if (outgoing) me.id else contactId
        append(
            Message(makeId(), sender, at, MessageContent.Call(summary), if (outgoing) DeliveryStatus.Sent else DeliveryStatus.Delivered, null, emptyList()),
            chatId,
        )
    }

    override fun safetyNumber(chatId: String): SafetyNumber? {
        if (chat(chatId) == null) return null
        // Fixed fake digits derived from the chat ID, the same as on iPhone; the real number comes from MLS keys.
        var seed = chatId.encodeToByteArray().fold(7u) { acc, b -> acc * 31u + b.toUByte().toUInt() }
        val groups = (0 until 12).map {
            seed = seed * 1_664_525u + 1_013_904_223u
            ((seed shr 8).toInt() % 100_000).toString().padStart(5, '0')
        }
        return SafetyNumber(groups, synchronized(lock) { chatId in verifiedChatIds })
    }

    override fun setVerified(verified: Boolean, chatId: String) = mutate {
        if (verified) verifiedChatIds += chatId else verifiedChatIds -= chatId
    }

    override fun receive(content: MessageContent, from: Contact) = mutate {
        val message = Message(makeId(), from.id, clock(), content, DeliveryStatus.Delivered, null, emptyList())
        val chatId = chatIdCreatingRequestIfNeeded(from)
        append(message, chatId)
        // Request chats keep their count too, so accepting shows the unseen messages; the lists leave requests out.
        change(chatId) { it.copy(unreadCount = it.unreadCount + 1) }
    }

    override fun accept(requestId: String) = mutate {
        val request = allChats.firstOrNull { it.id == requestId && it.isRequest } ?: return@mutate
        change(requestId) { it.copy(isRequest = false) }
        requestSenders.remove(request.contactId)?.let { _contacts.value = _contacts.value + it }
    }

    override fun block(contactId: String) = mutate {
        _blocked.value = _blocked.value + contactId
        allChats = allChats.filterNot { it.contactId == contactId }
        _contacts.value = _contacts.value.filterNot { it.id == contactId }
        requestSenders.remove(contactId)
    }

    override fun report(chatId: String) = mutate { _reported.value = _reported.value + chatId }

    override fun markRead(chatId: String) = mutate { change(chatId) { it.copy(unreadCount = 0) } }

    override fun incomingCall(fromAccountId: String, video: Boolean): IncomingCallDecision {
        if (_contacts.value.any { it.id == fromAccountId }) return IncomingCallDecision.Ring
        mutate {
            val caller = contact(fromAccountId) ?: Contact(fromAccountId, fromAccountId, fromAccountId)
            val chatId = chatIdCreatingRequestIfNeeded(caller)
            val summary = if (video) CallSummary.MissedVideo else CallSummary.MissedAudio
            append(Message(makeId(), caller.id, clock(), MessageContent.Call(summary), DeliveryStatus.Delivered, null, emptyList()), chatId)
        }
        return IncomingCallDecision.SilentMissed
    }

    override suspend fun addContact(username: String): Contact? {
        val name = username.lowercase()
        _contacts.value.firstOrNull { it.username == name }?.let { return it }
        val displayName = directory[name] ?: return null
        val contact = Contact(name, displayName, name)
        mutate { _contacts.value = _contacts.value + contact }
        return contact
    }

    override fun ensureChat(contactId: String): String {
        var id = ""
        mutate { id = ensureChatLocked(contactId) }
        return id
    }

    override fun addFolder(name: String, chatIds: Set<String>): ChatFolder {
        lateinit var folder: ChatFolder
        mutate {
            folder = ChatFolder("folder-${makeId()}", name, ChatFolder.Kind.Custom)
            _folders.value = _folders.value + folder
            allChats = allChats.map { if (it.id in chatIds) it.copy(folderIds = it.folderIds + folder.id) else it }
        }
        return folder
    }

    override fun renameFolder(id: String, name: String) = mutate {
        _folders.value = _folders.value.map { if (it.id == id && it.kind == ChatFolder.Kind.Custom) it.copy(name = name) else it }
    }

    override fun deleteFolder(id: String) = mutate {
        if (_folders.value.none { it.id == id && it.kind == ChatFolder.Kind.Custom }) return@mutate
        _folders.value = _folders.value.filterNot { it.id == id }
        allChats = allChats.map { it.copy(folderIds = it.folderIds - id) }
    }

    override fun moveFolder(id: String, toCustomIndex: Int) = mutate {
        val fixed = _folders.value.filter { it.kind != ChatFolder.Kind.Custom }
        val custom = _folders.value.filter { it.kind == ChatFolder.Kind.Custom }.toMutableList()
        val from = custom.indexOfFirst { it.id == id }
        if (from < 0) return@mutate
        val folder = custom.removeAt(from)
        custom.add(toCustomIndex.coerceIn(0, custom.size), folder)
        _folders.value = fixed + custom
    }

    // Private

    /** Runs a change under the lock, then republishes the chat lists. */
    private inline fun mutate(change: () -> Unit) {
        synchronized(lock) {
            change()
            publish()
        }
    }

    private fun publish() {
        _chats.value = allChats.filterNot { it.isRequest }
            .sortedByDescending { it.lastMessage?.sentAt ?: Instant.MIN }
        _requests.value = allChats.filter { it.isRequest }
    }

    private fun change(chatId: String, f: (Chat) -> Chat) {
        allChats = allChats.map { if (it.id == chatId) f(it) else it }
    }

    private fun updateMessage(messageId: String, chatId: String, f: (Message) -> Message) = mutate {
        change(chatId) { chat -> chat.copy(messages = chat.messages.map { if (it.id == messageId) f(it) else it }) }
    }

    private fun append(message: Message, chatId: String) = change(chatId) { it.copy(messages = it.messages + message) }

    private fun ensureChatLocked(contactId: String): String {
        allChats.firstOrNull { it.contactId == contactId }?.let { return it.id }
        val contact = contact(contactId) ?: return "chat-$contactId"
        return chatIdCreatingRequestIfNeeded(contact)
    }

    private fun chatIdCreatingRequestIfNeeded(sender: Contact): String {
        allChats.firstOrNull { it.contactId == sender.id }?.let { return it.id }
        val isContact = _contacts.value.any { it.id == sender.id }
        if (!isContact) requestSenders[sender.id] = sender
        val chat = Chat("chat-${sender.id}", sender.id, ChatEncryption.EndToEnd, emptySet(), 0, emptyList(), isRequest = !isContact)
        allChats = allChats + chat
        return chat.id
    }

    private fun makeId(): String {
        nextId += 1
        return "local-$nextId"
    }
}
