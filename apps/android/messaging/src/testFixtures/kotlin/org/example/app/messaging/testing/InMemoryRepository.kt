package org.example.app.messaging.testing

import org.example.app.messaging.ports.AttachmentRecord
import org.example.app.messaging.ports.CallRecord
import org.example.app.messaging.ports.ChatRecord
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.ports.ConversationMode
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.ports.FolderRecord
import org.example.app.messaging.ports.MemberRecord
import org.example.app.messaging.ports.MessageRecord
import org.example.app.messaging.ports.OutboxRecord
import org.example.app.messaging.ports.RawInboxRecord
import org.example.app.messaging.ports.ReactionRecord
import org.example.app.messaging.ports.Repository
import org.example.app.messaging.ports.SafetyRecord
import org.example.app.messaging.ports.SearchHit

/**
 * A [Repository] in memory with the same rules as `RoomRepository` (idempotent inserts, status ranks, sender
 * checks on edit and delete). [transaction] rolls every table back when its body throws.
 *
 * Test hooks: [failNextTransactions] makes the next outermost [transaction] calls throw after their body ran (a
 * failed commit; single statements outside a transaction are not affected), [onInsertRaw] observes raw inserts.
 */
class InMemoryRepository : Repository {
    private data class State(
        val contacts: MutableMap<String, ContactRecord> = linkedMapOf(),
        val chats: MutableMap<String, ChatRecord> = linkedMapOf(),
        val conversations: MutableMap<String, ConversationRecord> = sortedMapOf(),
        val members: MutableMap<String, List<MemberRecord>> = linkedMapOf(),
        val safety: MutableMap<String, SafetyRecord> = linkedMapOf(),
        val messages: MutableMap<String, MessageRecord> = linkedMapOf(),
        val reactions: MutableMap<Pair<String, String>, ReactionRecord> = linkedMapOf(),
        val attachments: MutableMap<String, AttachmentRecord> = linkedMapOf(),
        val outbox: MutableMap<String, OutboxRecord> = linkedMapOf(),
        val raw: java.util.TreeMap<Long, RawInboxRecord> = java.util.TreeMap(),
        val processed: MutableMap<String, Long> = linkedMapOf(),
        val folders: MutableMap<String, FolderRecord> = linkedMapOf(),
        val folderChats: MutableMap<String, List<String>> = linkedMapOf(),
        val blocked: MutableMap<String, Long> = linkedMapOf(),
        val calls: MutableList<CallRecord> = mutableListOf(),
        val kv: MutableMap<String, ByteArray> = sortedMapOf(),
    ) {
        fun copy(): State = State(
            LinkedHashMap(contacts), LinkedHashMap(chats), java.util.TreeMap(conversations), LinkedHashMap(members),
            LinkedHashMap(safety), LinkedHashMap(messages), LinkedHashMap(reactions), LinkedHashMap(attachments),
            LinkedHashMap(outbox), java.util.TreeMap(raw), LinkedHashMap(processed), LinkedHashMap(folders),
            LinkedHashMap(folderChats), LinkedHashMap(blocked), ArrayList(calls), java.util.TreeMap(kv),
        )
    }

    private val lock = Any()
    private var s = State()
    private var depth = 0

    @Volatile var failNextTransactions = 0
    var onInsertRaw: ((List<RawInboxRecord>) -> Unit)? = null
    /** Throws its result instead of writing the `kv` key, when not null (a failed database write). */
    @Volatile var failSet: ((String) -> Throwable?)? = null

    /** Number of outermost transactions that committed. */
    var commits = 0
        private set

    override fun <T> transaction(body: () -> T): T = synchronized(lock) {
        if (depth > 0) {
            depth += 1
            try { return body() } finally { depth -= 1 }
        }
        val saved = s.copy()
        depth = 1
        try {
            val result = body()
            if (failNextTransactions > 0) {
                failNextTransactions -= 1
                throw IllegalStateException("injected transaction failure")
            }
            commits += 1
            result
        } catch (e: Throwable) {
            s = saved
            throw e
        } finally {
            depth = 0
        }
    }

    /** A single statement: atomic, no injected failure. */
    private fun <T> tx(body: () -> T): T = synchronized(lock) { body() }

    // contacts
    override fun upsertContact(c: ContactRecord) = tx { s.contacts[c.accountId] = c }
    override fun contact(id: String) = tx { s.contacts[id] }
    override fun contacts() = tx { s.contacts.values.sortedBy { it.accountId } }

    // chats
    override fun upsertChat(c: ChatRecord) = tx { s.chats[c.id] = c }
    override fun chat(id: String) = tx { s.chats[id] }
    override fun chats() = tx { s.chats.values.sortedByDescending { it.lastActivity } }
    override fun setUnread(chatId: String, n: Int) = tx { s.chats[chatId]?.let { s.chats[chatId] = it.copy(unread = n) }; Unit }
    override fun setRequest(chatId: String, value: Boolean) = tx { s.chats[chatId]?.let { s.chats[chatId] = it.copy(isRequest = value) }; Unit }
    override fun deleteChat(id: String) = tx {
        s.chats.remove(id)
        val convs = s.conversations.values.filter { it.chatId == id }.map { it.serverId }
        convs.forEach { s.conversations.remove(it); s.members.remove(it) }
        val msgs = s.messages.values.filter { it.chatId == id }.map { it.id }.toSet()
        msgs.forEach { s.messages.remove(it) }
        s.reactions.keys.removeAll { it.first in msgs }
        s.attachments.values.removeAll { it.messageId in msgs }
        s.outbox.values.removeAll { it.chatId == id }
        s.safety.remove(id)
        Unit
    }

    // conversations
    private fun requireMode(m: String) = require(m in ConversationMode.all) { "mode" }
    override fun upsertConversation(c: ConversationRecord) = tx {
        requireMode(c.mode); c.innerMode?.let(::requireMode)
        // Room's unique index on group_id.
        require(s.conversations.values.none { it.serverId != c.serverId && it.groupId.contentEquals(c.groupId) }) { "group_id" }
        s.conversations[c.serverId] = c
    }
    override fun conversations(chatId: String) = tx { s.conversations.values.filter { it.chatId == chatId } }
    override fun conversation(serverId: String) = tx { s.conversations[serverId] }
    override fun conversationByGroup(groupId: ByteArray) = tx { s.conversations.values.firstOrNull { it.groupId.contentEquals(groupId) } }
    override fun preferredConversation(chatId: String) = tx { s.conversations.values.firstOrNull { it.chatId == chatId && it.active } }
    override fun setEpoch(serverId: String, epoch: Long) = tx { s.conversations[serverId]?.let { s.conversations[serverId] = it.copy(epoch = epoch) }; Unit }
    override fun pinInnerMode(serverId: String, mode: String): Boolean = tx {
        requireMode(mode)
        val c = s.conversations[serverId] ?: return@tx true
        when (c.innerMode) {
            mode -> true
            null -> { s.conversations[serverId] = c.copy(innerMode = mode); true }
            else -> { s.conversations[serverId] = c.copy(modeMismatch = true); false }
        }
    }
    override fun setServerMode(serverId: String, mode: String) = tx {
        requireMode(mode)
        s.conversations[serverId]?.let { s.conversations[serverId] = it.copy(mode = mode) }; Unit
    }
    override fun flagModeMismatch(serverId: String) = tx { s.conversations[serverId]?.let { s.conversations[serverId] = it.copy(modeMismatch = true) }; Unit }

    // members and safety
    override fun members(conversationId: String) = tx { s.members[conversationId].orEmpty().sortedBy { it.identity } }
    override fun replaceMembers(conversationId: String, members: List<MemberRecord>) = tx { s.members[conversationId] = members.toList() }
    override fun safety(chatId: String) = tx { s.safety[chatId] }
    override fun setSafetyCurrent(chatId: String, current: String) = tx { s.safety[chatId] = SafetyRecord(current, s.safety[chatId]?.verified) }
    override fun setVerified(chatId: String, verified: String?) = tx { s.safety[chatId]?.let { s.safety[chatId] = it.copy(verified = verified) }; Unit }

    // messages
    override fun insertMessage(m: MessageRecord): Boolean = tx {
        if (m.id in s.messages) false else { s.messages[m.id] = m; true }
    }
    override fun message(id: String) = tx { s.messages[id] }
    override fun messages(chatId: String, before: MessageRecord?, limit: Int) = tx {
        val order = compareBy<MessageRecord>({ it.sentAt }, { it.id })
        s.messages.values.filter { it.chatId == chatId }
            .filter { before == null || order.compare(it, before) < 0 }
            .sortedWith(order).takeLast(limit)
    }
    /** All messages in insertion order (tests). */
    fun allMessages(): List<MessageRecord> = tx { s.messages.values.toList() }

    override fun updateStatus(ids: List<String>, status: String) = tx {
        val rank = RANK[status]
        for (id in ids) {
            val m = s.messages[id] ?: continue
            val current = RANK[m.status] ?: -1
            if (rank == null || current < rank) s.messages[id] = m.copy(status = status)
        }
    }
    override fun applyEdit(id: String, sender: String, envelope: ByteArray, folded: String, at: Long): Boolean = tx {
        val m = s.messages[id]
        if (m == null || m.senderAccount != sender || m.deleted) false
        else { s.messages[id] = m.copy(envelope = envelope, folded = folded, editedAt = at); true }
    }
    override fun markDeleted(id: String, sender: String): Boolean = tx {
        val m = s.messages[id]
        if (m == null || m.senderAccount != sender) false
        else { s.messages[id] = m.copy(deleted = true, envelope = ByteArray(0), folded = ""); true }
    }
    override fun setReaction(messageId: String, accountId: String, emoji: String) = tx {
        if (emoji.isEmpty()) s.reactions.remove(messageId to accountId)
        else s.reactions[messageId to accountId] = ReactionRecord(messageId, accountId, emoji)
        Unit
    }
    override fun reactions(messageId: String) = tx { s.reactions.values.filter { it.messageId == messageId }.sortedBy { it.accountId } }
    override fun search(query: String, limit: Int) = tx {
        val q = query.lowercase()
        if (q.isEmpty()) emptyList()
        else s.messages.values.filter { !it.deleted && it.folded.contains(q) }.sortedByDescending { it.sentAt }
            .take(limit).map { SearchHit(it.chatId, it.id) }
    }

    // attachments
    override fun upsertAttachment(a: AttachmentRecord) = tx { s.attachments[a.id] = a }
    override fun attachment(id: String) = tx { s.attachments[id] }
    override fun attachmentForMessage(messageId: String) = tx { s.attachments.values.filter { it.messageId == messageId }.minByOrNull { it.id } }
    override fun setAttachmentState(id: String, state: String, downloaded: Long, localPath: String?) = tx {
        s.attachments[id]?.let { s.attachments[id] = AttachmentRecord(it.id, it.messageId, it.kind, it.key, it.digest, it.size, it.cipherSize, state, downloaded, localPath) }
        Unit
    }

    // outbox
    override fun enqueue(o: OutboxRecord) = tx { s.outbox[o.messageId] = o }
    override fun enqueueSend(message: MessageRecord, outbox: OutboxRecord) = tx { insertMessage(message); enqueue(outbox) }
    override fun dueOutbox(at: Long) = tx { s.outbox.values.filter { it.nextAttemptAt <= at }.sortedWith(compareBy({ it.nextAttemptAt }, { it.messageId })) }
    override fun dueOutboxHeads(at: Long) = tx {
        s.outbox.values.groupBy { it.chatId }.values.map { it.first() }.filter { it.nextAttemptAt <= at }
    }
    override fun completeSend(id: String, status: String) = tx { s.outbox.remove(id); updateStatus(listOf(id), status) }
    override fun requeueSend(id: String, now: Long): Boolean = tx {
        val m = s.messages[id] ?: return@tx false
        if (m.status != "failed") return@tx false
        s.messages[id] = m.copy(status = "sending", receivedAt = now)
        s.outbox[id] = OutboxRecord(id, m.chatId, m.envelope, 0, now)
        true
    }
    override fun bumpOutbox(id: String, nextAttemptAt: Long) = tx { s.outbox[id]?.let { s.outbox[id] = it.copy(attempts = it.attempts + 1, nextAttemptAt = nextAttemptAt) }; Unit }
    override fun removeOutbox(id: String) = tx { s.outbox.remove(id); Unit }

    // raw inbox and processed hashes
    override fun insertRaw(items: List<RawInboxRecord>) = tx {
        onInsertRaw?.invoke(items)
        items.forEach { s.raw.putIfAbsent(it.cursor, it) }
    }
    override fun rawItems(limit: Int) = tx { s.raw.values.take(limit) }
    override fun deleteRaw(cursor: Long) = tx { s.raw.remove(cursor); Unit }
    override fun markProcessed(hash: ByteArray, at: Long): Boolean = tx {
        val key = hash.hex()
        if (key in s.processed) false else { s.processed[key] = at; true }
    }
    override fun isProcessed(hash: ByteArray) = tx { hash.hex() in s.processed }
    override fun pruneProcessed(olderThan: Long) = tx { s.processed.values.removeAll { it < olderThan }; Unit }
    fun processedCount(): Int = tx { s.processed.size }

    // folders
    override fun folders() = tx { s.folders.values.sortedWith(compareBy({ it.position }, { it.id })) }
    override fun addFolder(f: FolderRecord) = tx { s.folders[f.id] = f }
    override fun renameFolder(id: String, name: String) = tx { s.folders[id]?.let { s.folders[id] = it.copy(name = name) }; Unit }
    override fun deleteFolder(id: String) = tx { s.folders.remove(id); s.folderChats.remove(id); Unit }
    override fun moveFolder(id: String, to: Int) = tx { s.folders[id]?.let { s.folders[id] = it.copy(position = to) }; Unit }
    override fun setFolderChats(folderId: String, chatIds: List<String>) = tx { s.folderChats[folderId] = chatIds.toList() }
    override fun folderChats(folderId: String) = tx { s.folderChats[folderId].orEmpty() }

    // blocked
    override fun block(accountId: String, at: Long) = tx { s.blocked.putIfAbsent(accountId, at); Unit }
    override fun unblock(accountId: String) = tx { s.blocked.remove(accountId); Unit }
    override fun isBlocked(accountId: String) = tx { accountId in s.blocked }
    override fun blockedIds() = tx { s.blocked.keys.sorted() }

    // calls
    override fun insertCall(c: CallRecord) = tx { s.calls.removeAll { it.id == c.id }; s.calls.add(c); Unit }
    override fun calls(limit: Int) = tx { s.calls.sortedByDescending { it.startedAt }.take(limit) }

    // key-value
    override fun get(key: String) = tx { s.kv[key] }
    override fun set(key: String, value: ByteArray) = tx { failSet?.invoke(key)?.let { throw it }; s.kv[key] = value }
    override fun entries(prefix: String) = tx { s.kv.filterKeys { it.startsWith(prefix) } }

    override fun wipe() = tx { s = State() }

    private companion object {
        val RANK = mapOf("sending" to 0, "sent" to 1, "delivered" to 2, "read" to 3)
        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
