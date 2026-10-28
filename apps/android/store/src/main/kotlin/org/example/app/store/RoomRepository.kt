package org.example.app.store

import androidx.sqlite.db.SimpleSQLiteQuery
import org.example.app.messaging.ports.AttachmentRecord
import org.example.app.messaging.ports.AttachmentState
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

/** [Repository] on Room. Blocking calls; callers run them on an I/O dispatcher. */
class RoomRepository(private val db: AppDatabase, allowLikeFallback: Boolean = false) : Repository {
    private val contacts = db.contacts()
    private val chats = db.chats()
    private val conversations = db.conversations()
    private val safety = db.safety()
    private val messages = db.messages()
    private val outbox = db.outbox()
    private val misc = db.misc()

    /** False only when the database was made without FTS5, which is allowed solely for JVM tests. */
    private val hasFts: Boolean =
        db.openHelper.readableDatabase.query("SELECT 1 FROM sqlite_master WHERE name = 'messages_fts'").use { it.moveToFirst() }

    init {
        check(hasFts || allowLikeFallback) { "The search index is missing; refusing to fall back to LIKE" }
    }

    override fun <T> transaction(body: () -> T): T = db.runInTransaction<T>(body)

    // contacts
    override fun upsertContact(c: ContactRecord) =
        contacts.upsert(ContactEntity(c.accountId, c.username, c.displayName, c.isContact, c.addedAt))
    override fun contact(id: String) = contacts.get(id)?.toRecord()
    override fun contacts() = contacts.all().map { it.toRecord() }

    // chats
    override fun upsertChat(c: ChatRecord) = chats.upsert(ChatEntity(c.id, c.isRequest, c.unread, c.muted, c.lastActivity))
    override fun chat(id: String) = chats.get(id)?.toRecord()
    override fun chats() = chats.all().map { it.toRecord() }
    override fun setUnread(chatId: String, n: Int) = chats.setUnread(chatId, n)
    override fun setRequest(chatId: String, value: Boolean) = chats.setRequest(chatId, value)
    override fun deleteChat(id: String) = transaction {
        chats.deleteOutbox(id)
        chats.delete(id)
    }

    // conversations
    override fun upsertConversation(c: ConversationRecord) {
        requireMode(c.mode)
        c.innerMode?.let(::requireMode)
        conversations.upsert(
        ConversationEntity(c.serverId, c.chatId, c.groupId, c.mode, c.innerMode, c.modeMismatch, c.epoch, c.active, c.createdAt),
        )
    }
    override fun conversations(chatId: String) = conversations.forChat(chatId).map { it.toRecord() }
    override fun conversation(serverId: String) = conversations.get(serverId)?.toRecord()
    override fun conversationByGroup(groupId: ByteArray) = conversations.byGroup(groupId)?.toRecord()
    override fun preferredConversation(chatId: String) = conversations.preferred(chatId)?.toRecord()
    override fun setEpoch(serverId: String, epoch: Long) = conversations.setEpoch(serverId, epoch)
    override fun pinInnerMode(serverId: String, mode: String): Boolean {
        requireMode(mode)
        return pin(serverId, mode)
    }
    private fun pin(serverId: String, mode: String): Boolean = transaction {
        val current = conversations.get(serverId)?.innerMode
        when {
            current == mode -> true
            current != null -> {
                conversations.flagMismatch(serverId)
                false
            }
            else -> {
                conversations.setInnerMode(serverId, mode)
                true
            }
        }
    }
    override fun setServerMode(serverId: String, mode: String) {
        requireMode(mode)
        conversations.setMode(serverId, mode)
    }
    override fun flagModeMismatch(serverId: String) = conversations.flagMismatch(serverId)

    // members and safety
    override fun members(conversationId: String) =
        conversations.members(conversationId).map { MemberRecord(it.identity, it.signatureKey) }
    override fun replaceMembers(conversationId: String, members: List<MemberRecord>) = transaction {
        conversations.clearMembers(conversationId)
        conversations.insertMembers(members.map { MemberEntity(conversationId, it.identity, it.signatureKey) })
    }
    override fun safety(chatId: String) = safety.get(chatId)?.let { SafetyRecord(it.current, it.verified) }
    override fun setSafetyCurrent(chatId: String, current: String) = safety.setCurrent(chatId, current)
    override fun setVerified(chatId: String, verified: String?) = safety.setVerified(chatId, verified)

    // messages
    override fun insertMessage(m: MessageRecord): Boolean = messages.insert(m.toEntity()) != -1L
    override fun message(id: String) = messages.get(id)?.toRecord()
    override fun messages(chatId: String, before: MessageRecord?, limit: Int): List<MessageRecord> {
        val rows = if (before == null) messages.newest(chatId, limit) else messages.olderThan(chatId, before.sentAt, before.id, limit)
        return rows.map { it.toRecord() }.reversed()
    }
    override fun updateStatus(ids: List<String>, status: String) {
        if (ids.isEmpty()) return
        transaction {
            // SQLite allows 32766 variables; chunk well below it.
            ids.chunked(500).forEach { chunk ->
                val rank = STATUS_RANK[status]
                if (rank != null) messages.raiseStatus(chunk, status, rank) else messages.forceStatus(chunk, status)
            }
        }
    }
    override fun applyEdit(id: String, sender: String, envelope: ByteArray, folded: String, at: Long) =
        messages.applyEdit(id, sender, envelope, folded, at) > 0
    override fun markDeleted(id: String, sender: String) = messages.markDeleted(id, sender) > 0
    override fun setReaction(messageId: String, accountId: String, emoji: String) {
        if (emoji.isEmpty()) messages.removeReaction(messageId, accountId)
        else messages.setReaction(ReactionEntity(messageId, accountId, emoji))
    }
    override fun reactions(messageId: String) = messages.reactions(messageId).map { ReactionRecord(it.messageId, it.accountId, it.emoji) }

    override fun search(query: String, limit: Int): List<SearchHit> {
        val q = TextFold.fold(query)
        if (q.isEmpty()) return emptyList()
        if (q.codePointCount(0, q.length) < 3 || !hasFts) {
            val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            return messages.searchLike("%$escaped%", limit).map { SearchHit(it.chatId, it.id) }
        }
        // The trigram tokenizer matches a quoted phrase as a substring of 3 or more characters.
        val phrase = "\"" + q.replace("\"", "\"\"") + "\""
        val sql = "SELECT m.chat_id, m.id FROM messages_fts JOIN messages m ON m.rowid = messages_fts.rowid " +
            "WHERE messages_fts MATCH ? AND m.deleted = 0 ORDER BY m.sent_at DESC LIMIT ?"
        db.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, arrayOf<Any?>(phrase, limit))).use { c ->
            val hits = ArrayList<SearchHit>()
            while (c.moveToNext()) hits.add(SearchHit(c.getString(0), c.getString(1)))
            return hits
        }
    }

    // attachments
    override fun upsertAttachment(a: AttachmentRecord) {
        requireState(a.state)
        messages.upsertAttachment(
            AttachmentEntity(a.id, a.messageId, a.kind, a.key, a.digest, a.size, a.cipherSize, a.state, a.downloaded, a.localPath),
        )
    }
    override fun attachment(id: String) = messages.attachment(id)?.toRecord()
    override fun attachmentForMessage(messageId: String) = messages.attachmentForMessage(messageId)?.toRecord()
    override fun setAttachmentState(id: String, state: String, downloaded: Long, localPath: String?) {
        requireState(state)
        messages.setAttachmentState(id, state, downloaded, localPath)
    }

    // outbox
    override fun enqueue(o: OutboxRecord) = outbox.enqueue(o.toEntity())
    override fun enqueueSend(message: MessageRecord, outbox: OutboxRecord) = transaction {
        insertMessage(message)
        enqueue(outbox)
    }
    override fun dueOutbox(at: Long) = outbox.due(at).map { it.toRecord() }
    override fun dueOutboxHeads(at: Long) = outbox.dueHeads(at).map { it.toRecord() }
    override fun completeSend(id: String, status: String) = transaction {
        updateStatus(listOf(id), status)
        outbox.remove(id)
    }
    override fun requeueSend(id: String, now: Long): Boolean = transaction {
        val m = messages.get(id) ?: return@transaction false
        messages.resetForRetry(id, now)
        enqueue(OutboxRecord(id, m.chatId, m.envelope, 0, now))
        true
    }
    override fun bumpOutbox(id: String, nextAttemptAt: Long) = outbox.bump(id, nextAttemptAt)
    override fun removeOutbox(id: String) = outbox.remove(id)

    // raw inbox and processed hashes
    override fun insertRaw(items: List<RawInboxRecord>) = outbox.insertRaw(items.map { it.toEntity() })
    override fun rawItems(limit: Int) = outbox.raw(limit).map { it.toRecord() }
    override fun deleteRaw(cursor: Long) = outbox.deleteRaw(cursor)
    override fun markProcessed(hash: ByteArray, at: Long): Boolean = outbox.markProcessed(ProcessedEntity(hash, at)) != -1L
    override fun isProcessed(hash: ByteArray) = outbox.processedCount(hash) > 0
    override fun pruneProcessed(olderThan: Long) = outbox.pruneProcessed(olderThan)

    // folders
    override fun folders() = misc.folders().map { FolderRecord(it.id, it.name, it.position) }
    override fun addFolder(f: FolderRecord) = misc.addFolder(FolderEntity(f.id, f.name, f.position))
    override fun renameFolder(id: String, name: String) = misc.renameFolder(id, name)
    override fun deleteFolder(id: String) = misc.deleteFolder(id)
    override fun moveFolder(id: String, to: Int): Unit = transaction {
        val ids = misc.folders().map { it.id }.toMutableList()
        if (ids.remove(id)) {
            ids.add(to.coerceIn(0, ids.size), id)
            ids.forEachIndexed { i, fid -> misc.setPosition(fid, i) }
        }
        Unit
    }
    override fun setFolderChats(folderId: String, chatIds: List<String>) = transaction {
        misc.clearFolderChats(folderId)
        misc.addFolderChats(chatIds.map { FolderChatEntity(folderId, it) })
    }
    override fun folderChats(folderId: String) = misc.folderChats(folderId)

    // blocked
    override fun block(accountId: String, at: Long) = misc.block(BlockedEntity(accountId, at))
    override fun unblock(accountId: String) = misc.unblock(accountId)
    override fun isBlocked(accountId: String) = misc.blockedCount(accountId) > 0
    override fun blockedIds() = misc.blockedIds()

    // calls
    override fun insertCall(c: CallRecord) =
        misc.insertCall(CallEntity(c.id, c.chatId, c.video, c.outgoing, c.outcome, c.startedAt, c.durationSeconds))
    override fun calls(limit: Int) =
        misc.calls(limit).map { CallRecord(it.id, it.chatId, it.video, it.outgoing, it.outcome, it.startedAt, it.duration) }

    // key-value
    override fun get(key: String) = misc.kv(key)?.value
    override fun set(key: String, value: ByteArray) = misc.setKv(KvEntity(key, value))
    override fun entries(prefix: String): Map<String, ByteArray> =
        misc.kvPrefix(prefix, prefix.length).associate { it.key to it.value }

    override fun wipe() {
        transaction {
            // Children go with their chats, folders and messages by cascade.
            db.openHelper.writableDatabase.execSQL("DELETE FROM outbox")
            db.openHelper.writableDatabase.let { w ->
                listOf("messages", "chats", "contacts", "inbox_raw", "processed", "folders", "blocked", "kv", "calls").forEach {
                    w.execSQL("DELETE FROM $it")
                }
            }
        }
        // Drop deleted content from the write-ahead log file as well.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
    }

    private fun requireMode(mode: String) = require(mode in ConversationMode.all) { "Unknown conversation mode" }
    private fun requireState(state: String) = require(state in AttachmentState.all) { "Unknown attachment state" }

    private companion object {
        val STATUS_RANK = mapOf("sending" to 0, "sent" to 1, "delivered" to 2, "read" to 3)
    }
}

private fun ContactEntity.toRecord() = ContactRecord(accountId, username, displayName, isContact, addedAt)
private fun ChatEntity.toRecord() = ChatRecord(id, isRequest, unread, muted, lastActivity)
private fun ConversationEntity.toRecord() = ConversationRecord(serverId, chatId, groupId, mode, innerMode, modeMismatch, epoch, active, createdAt)
private fun MessageRecord.toEntity() = MessageEntity(
    id, chatId, conversationId, senderAccount, senderDevice, sentAt, receivedAt, envelope, status, replyTo, editedAt,
    deleted, undecryptable, folded,
)
private fun MessageEntity.toRecord() = MessageRecord(
    id, chatId, conversationId, senderAccount, senderDevice, sentAt, receivedAt, envelope, status, replyTo, editedAt,
    deleted, undecryptable, folded,
)
private fun AttachmentEntity.toRecord() = AttachmentRecord(id, messageId, kind, key, digest, size, cipherSize, state, downloaded, localPath)
private fun OutboxRecord.toEntity() = OutboxEntity(messageId, chatId, envelope, attempts, nextAttemptAt)
private fun OutboxEntity.toRecord() = OutboxRecord(messageId, chatId, envelope, attempts, nextAttemptAt)
private fun RawInboxRecord.toEntity() = InboxRawEntity(cursor, kind, conversationId, seq, epoch, senderAccount, senderDevice, payload, sentAt)
private fun InboxRawEntity.toRecord() = RawInboxRecord(cursor, kind, conversationId, seq, epoch, senderAccount, senderDevice, payload, sentAt)
