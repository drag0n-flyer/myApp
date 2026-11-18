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
    private val meFlow = MutableStateFlow(data.me)
    override val me: Contact get() = meFlow.value
    override val meState: StateFlow<Contact> = meFlow.asStateFlow()

    override val myStoredName: String get() = me.displayName
    override fun setMyDisplayName(name: String) { meFlow.value = meFlow.value.copy(displayName = name) }

    private val _profiles = MutableStateFlow<Map<String, org.example.app.core.people.Profile>>(emptyMap())
    override val profiles: StateFlow<Map<String, org.example.app.core.people.Profile>> = _profiles.asStateFlow()
    /** Gives a sample person a profile (a photo for the tiles). Sample data only. */
    fun putProfile(profile: org.example.app.core.people.Profile) { _profiles.value = _profiles.value + (profile.accountId to profile) }
    override fun setMyPhoto(photo: ByteArray?) {
        _profiles.value = if (photo == null) _profiles.value - me.id
        else _profiles.value + (me.id to org.example.app.core.people.Profile(me.id, me.displayName, photo, 1))
    }

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
    private val _muted = MutableStateFlow<Set<String>>(emptySet())
    override val mutedChatIds: StateFlow<Set<String>> = _muted.asStateFlow()
    override val supportsSafetyNumbers: Boolean = true

    init {
        publish()
    }

    override fun setPinned(chatId: String, pinned: Boolean): Boolean = synchronized(lock) {
        val chat = allChats.firstOrNull { it.id == chatId } ?: return false
        if (!pinned) {
            mutate { change(chatId) { it.copy(pinnedAt = null) } }
            return true
        }
        if (chat.archived) return false // an archived chat is never pinned
        if (chat.pinnedAt != null) return true
        if (allChats.count { it.pinnedAt != null && !it.archived } >= ChatListOrder.MAX_PINS) return false
        mutate { change(chatId) { it.copy(pinnedAt = clock()) } }
        true
    }

    override fun setMuted(chatId: String, muted: Boolean) = mutate { change(chatId) { it.copy(muted = muted) } }

    /** Archiving also unpins; unarchiving does not pin again. */
    override fun setArchived(chatId: String, archived: Boolean) = mutate {
        change(chatId) { it.copy(archived = archived, pinnedAt = if (archived) null else it.pinnedAt) }
    }

    override fun setDraft(chatId: String, text: String?) = mutate {
        change(chatId) { it.copy(draft = text?.takeIf { t -> t.isNotBlank() }) }
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
                hits += MessageHit(chat.id, message.id, MessageFormat.plain(content.text), message.sentAt)
                if (hits.size == limit) return hits
            }
        }
        return hits
    }

    override fun send(content: MessageContent, chatId: String, replyTo: String?) = mutate {
        val at = clock()
        // Under a timer the message disappears at `at + timer`, as in the live store.
        val ttl = allChats.firstOrNull { it.id == chatId }?.timerSeconds ?: 0
        val expiresAt = org.example.app.core.people.Expiry.expiresAt(at.toEpochMilli(), ttl.toLong().takeIf { it > 0 })?.let(Instant::ofEpochMilli)
        append(Message(makeId(), me.id, at, content, DeliveryStatus.Sent, replyTo, emptyList(), expiresAt = expiresAt), chatId)
    }

    override fun timerSeconds(chatId: String): Int = chat(chatId)?.timerSeconds ?: 0

    override fun setTimer(chatId: String, seconds: Int) = mutate {
        if (org.example.app.core.people.TimerChoice.from(seconds) == null) return@mutate
        val current = allChats.firstOrNull { it.id == chatId } ?: return@mutate
        if (current.timerSeconds == seconds) return@mutate
        change(chatId) { it.copy(timerSeconds = seconds) }
        val line = Message(makeId(), "system", clock(), MessageContent.System(SystemEvent.Timer(seconds, null)), DeliveryStatus.Sent, null, emptyList())
        append(line, chatId)
    }

    override fun sweepExpired(now: Long): List<String> {
        val removed = mutableListOf<String>()
        mutate {
            allChats = allChats.map { chat ->
                val gone = chat.messages.filter { org.example.app.core.people.Expiry.isExpired(it.expiresAt?.toEpochMilli(), now) }
                if (gone.isEmpty()) {
                    chat
                } else {
                    removed += gone.map { it.id }
                    val unreadGone = gone.count { it.senderId != me.id && it.senderId != "system" && it.content !is MessageContent.System }
                    chat.copy(messages = chat.messages - gone.toSet(), unreadCount = maxOf(0, chat.unreadCount - unreadGone))
                }
            }
        }
        return removed
    }

    override fun sendAttachment(path: String, kind: MediaKind, chatId: String) = sendAttachment(path, kind, chatId, null)

    override fun sendAttachment(path: String, kind: MediaKind, chatId: String, caption: String?) {
        val c = caption?.takeIf { it.isNotBlank() }
        val content = when (kind) {
            MediaKind.Photo -> MessageContent.Photo(0, c)
            MediaKind.Video -> MessageContent.File(ChatMedia.videoFileName(path.substringAfterLast('/'), null), 0, "video/mp4", c)
            MediaKind.Voice -> MessageContent.Voice(0.0)
            MediaKind.File -> MessageContent.File(path.substringAfterLast('/'), 0, caption = c)
        }
        send(content, chatId, null)
    }

    /** Sample data has no uploads; a message of mine that shows as sending is removed, anything else stays. */
    override fun cancelUpload(messageId: String) = mutate {
        allChats = allChats.map { chat ->
            if (chat.messages.any { it.id == messageId && it.senderId == me.id && it.status == DeliveryStatus.Sending }) {
                chat.copy(messages = chat.messages.filterNot { it.id == messageId })
            } else {
                chat
            }
        }
    }

    override fun react(emoji: String, messageId: String, chatId: String) = updateMessage(messageId, chatId) {
        if (emoji in it.reactions) it.copy(reactions = it.reactions - emoji) else it.copy(reactions = it.reactions + emoji)
    }

    override fun edit(messageId: String, chatId: String, text: String) = updateMessage(messageId, chatId) {
        val c = it.content
        if (it.senderId != me.id) it
        else when {
            c is MessageContent.Text -> it.copy(content = MessageContent.Text(text, c.preview?.takeIf { p -> p.url in text }), editedAt = clock())
            c is MessageContent.Photo && c.caption != null -> it.copy(content = c.copy(caption = text), editedAt = clock())
            c is MessageContent.File && c.caption != null -> it.copy(content = c.copy(caption = text), editedAt = clock())
            else -> it
        }
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

    override fun unblock(contactId: String) = mutate { _blocked.value = _blocked.value - contactId }

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

    override suspend fun createGroup(contactIds: List<String>, name: String, photo: ByteArray?): GroupCreated {
        val title = org.example.app.core.groups.GroupNameRule.clean(name)
        if (contactIds.size !in 2..99 || contactIds.toSet().size != contactIds.size || title == null) {
            throw GroupCreateFailed(GroupCreateFailed.Reason.Other)
        }
        var id = ""
        mutate {
            nextId += 1
            id = org.example.app.core.groups.ChatID.group("sample-$nextId")
            allChats = allChats + Chat(id, null, ChatEncryption.EndToEnd, emptySet(), 0, emptyList(), isRequest = false, title = title)
        }
        return GroupCreated(id, emptyList())
    }

    private val sampleReceipts = HashMap<String, List<MessageReceipt>>()

    override fun messageReceipts(messageId: String): List<MessageReceipt> = synchronized(lock) { sampleReceipts[messageId].orEmpty() }

    /**
     * Adds the group "Weekend plans" with Riya and Arjun and a few messages (two senders, one of mine that Riya read and
     * Arjun got), for demos and sample-mode UI tests. It is not in [SampleData.default]. Returns the chat ID.
     */
    fun addSampleGroup(): String {
        var id = ""
        mutate {
            nextId += 1
            id = org.example.app.core.groups.ChatID.group("sample-weekend-$nextId")
            val now = clock()
            fun at(minutesAgo: Long) = now.minusSeconds(minutesAgo * 60)
            fun msg(key: String, sender: String, minutes: Long, text: String, status: DeliveryStatus = DeliveryStatus.Delivered) =
                Message("$id-$key", sender, at(minutes), MessageContent.Text(text), status, null, emptyList())
            val mine = msg("m3", me.id, 5, "I can bring the drinks", DeliveryStatus.Read)
            sampleReceipts[mine.id] = listOf(
                MessageReceipt("riya", at(4), at(3)),
                MessageReceipt("arjun", at(4), null),
            )
            val messages = listOf(
                msg("m1", "riya", 9, "Who is bringing snacks on Saturday?"),
                msg("m2", "riya", 8, "I can do the cake"),
                msg("m4", "arjun", 6, "Count me in for the snacks"),
                mine,
            )
            allChats = allChats + Chat(id, null, ChatEncryption.EndToEnd, emptySet(), 0, messages, isRequest = false, title = "Weekend plans")
        }
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
        _chats.value = ChatListOrder.sorted(allChats.filterNot { it.isRequest })
        _muted.value = allChats.filter { it.muted }.map { it.id }.toSet()
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
