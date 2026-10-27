package org.example.app.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ContactDao {
    @Upsert fun upsert(c: ContactEntity)
    @Query("SELECT * FROM contacts WHERE account_id = :id") fun get(id: String): ContactEntity?
    @Query("SELECT * FROM contacts ORDER BY display_name COLLATE NOCASE, account_id") fun all(): List<ContactEntity>
}

@Dao
interface ChatDao {
    @Upsert fun upsert(c: ChatEntity)
    @Query("SELECT * FROM chats WHERE id = :id") fun get(id: String): ChatEntity?
    @Query("SELECT * FROM chats ORDER BY last_activity DESC, id") fun all(): List<ChatEntity>
    @Query("UPDATE chats SET unread = :n WHERE id = :id") fun setUnread(id: String, n: Int)
    @Query("UPDATE chats SET is_request = :v WHERE id = :id") fun setRequest(id: String, v: Boolean)
    @Query("DELETE FROM outbox WHERE chat_id = :id") fun deleteOutbox(id: String)
    @Query("DELETE FROM chats WHERE id = :id") fun delete(id: String)
}

@Dao
interface ConversationDao {
    // Not REPLACE: that would delete the row and cascade away its members.
    @Upsert fun upsert(c: ConversationEntity)
    @Query("SELECT * FROM conversations WHERE chat_id = :chatId ORDER BY server_id") fun forChat(chatId: String): List<ConversationEntity>
    @Query("SELECT * FROM conversations WHERE server_id = :id") fun get(id: String): ConversationEntity?
    @Query("SELECT * FROM conversations WHERE group_id = :groupId") fun byGroup(groupId: ByteArray): ConversationEntity?
    @Query("SELECT * FROM conversations WHERE chat_id = :chatId AND active = 1 ORDER BY server_id LIMIT 1")
    fun preferred(chatId: String): ConversationEntity?
    @Query("UPDATE conversations SET epoch = :epoch WHERE server_id = :id") fun setEpoch(id: String, epoch: Long)
    @Query("UPDATE conversations SET mode = :mode WHERE server_id = :id") fun setMode(id: String, mode: String)
    @Query("UPDATE conversations SET inner_mode = :mode WHERE server_id = :id") fun setInnerMode(id: String, mode: String)
    @Query("UPDATE conversations SET mode_mismatch = 1 WHERE server_id = :id") fun flagMismatch(id: String)

    @Query("SELECT * FROM members WHERE conversation_id = :id ORDER BY identity") fun members(id: String): List<MemberEntity>
    @Query("DELETE FROM members WHERE conversation_id = :id") fun clearMembers(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertMembers(m: List<MemberEntity>)
}

@Dao
interface SafetyDao {
    @Query("SELECT * FROM safety WHERE chat_id = :chatId") fun get(chatId: String): SafetyEntity?
    @Query(
        "INSERT INTO safety(chat_id, current, verified) VALUES (:chatId, :current, NULL) " +
            "ON CONFLICT(chat_id) DO UPDATE SET current = excluded.current",
    )
    fun setCurrent(chatId: String, current: String)
    @Query("UPDATE safety SET verified = :verified WHERE chat_id = :chatId") fun setVerified(chatId: String, verified: String?)
}

@Dao
interface MessageDao {
    /** IGNORE makes a repeated message ID a no-op; the return value is the new row ID or -1. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(m: MessageEntity): Long
    @Query("SELECT * FROM messages WHERE id = :id") fun get(id: String): MessageEntity?
    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY sent_at DESC, id DESC LIMIT :limit")
    fun newest(chatId: String, limit: Int): List<MessageEntity>
    @Query(
        "SELECT * FROM messages WHERE chat_id = :chatId AND (sent_at < :sentAt OR (sent_at = :sentAt AND id < :id)) " +
            "ORDER BY sent_at DESC, id DESC LIMIT :limit",
    )
    fun olderThan(chatId: String, sentAt: Long, id: String, limit: Int): List<MessageEntity>
    @Query(
        "UPDATE messages SET status = :status WHERE id IN (:ids) AND " +
            "CASE status WHEN 'sending' THEN 0 WHEN 'sent' THEN 1 WHEN 'delivered' THEN 2 WHEN 'read' THEN 3 ELSE -1 END < :rank",
    )
    fun raiseStatus(ids: List<String>, status: String, rank: Int)
    @Query("UPDATE messages SET status = :status WHERE id IN (:ids)") fun forceStatus(ids: List<String>, status: String)
    @Query("UPDATE messages SET envelope = :envelope, folded = :folded, edited_at = :at WHERE id = :id AND sender_account = :sender AND deleted = 0")
    fun applyEdit(id: String, sender: String, envelope: ByteArray, folded: String, at: Long): Int
    @Query("UPDATE messages SET deleted = 1, envelope = x'', folded = '' WHERE id = :id AND sender_account = :sender")
    fun markDeleted(id: String, sender: String): Int
    @Query("UPDATE messages SET status = 'sending', received_at = :now WHERE id = :id") fun resetForRetry(id: String, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun setReaction(r: ReactionEntity)
    @Query("DELETE FROM reactions WHERE message_id = :messageId AND account_id = :accountId") fun removeReaction(messageId: String, accountId: String)
    @Query("SELECT * FROM reactions WHERE message_id = :messageId ORDER BY account_id") fun reactions(messageId: String): List<ReactionEntity>

    @Query("SELECT chat_id, id FROM messages WHERE deleted = 0 AND folded LIKE :pattern ESCAPE '\\' ORDER BY sent_at DESC LIMIT :limit")
    fun searchLike(pattern: String, limit: Int): List<SearchRow>

    @Upsert fun upsertAttachment(a: AttachmentEntity)
    @Query("SELECT * FROM attachments WHERE id = :id") fun attachment(id: String): AttachmentEntity?
    @Query("SELECT * FROM attachments WHERE message_id = :messageId ORDER BY id LIMIT 1") fun attachmentForMessage(messageId: String): AttachmentEntity?
    @Query("UPDATE attachments SET state = :state, downloaded = :downloaded, local_path = :path WHERE id = :id")
    fun setAttachmentState(id: String, state: String, downloaded: Long, path: String?)
}

data class SearchRow(
    @androidx.room.ColumnInfo(name = "chat_id") val chatId: String,
    @androidx.room.ColumnInfo(name = "id") val id: String,
)

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun enqueue(o: OutboxEntity)
    @Query("SELECT * FROM outbox WHERE next_attempt_at <= :at ORDER BY next_attempt_at, message_id") fun due(at: Long): List<OutboxEntity>
    @Query(
        "SELECT * FROM outbox o WHERE o.rowid = (SELECT MIN(rowid) FROM outbox WHERE chat_id = o.chat_id) " +
            "AND next_attempt_at <= :at ORDER BY o.rowid",
    )
    fun dueHeads(at: Long): List<OutboxEntity>
    @Query("UPDATE outbox SET attempts = attempts + 1, next_attempt_at = :next WHERE message_id = :id") fun bump(id: String, next: Long)
    @Query("DELETE FROM outbox WHERE message_id = :id") fun remove(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertRaw(items: List<InboxRawEntity>)
    @Query("SELECT * FROM inbox_raw ORDER BY cursor LIMIT :limit") fun raw(limit: Int): List<InboxRawEntity>
    @Query("DELETE FROM inbox_raw WHERE cursor = :cursor") fun deleteRaw(cursor: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun markProcessed(p: ProcessedEntity): Long
    @Query("SELECT COUNT(*) FROM processed WHERE payload_hash = :hash") fun processedCount(hash: ByteArray): Int
    @Query("DELETE FROM processed WHERE processed_at < :before") fun pruneProcessed(before: Long)
}

@Dao
interface MiscDao {
    @Query("SELECT * FROM folders ORDER BY position, id") fun folders(): List<FolderEntity>
    @Insert fun addFolder(f: FolderEntity)
    @Query("UPDATE folders SET name = :name WHERE id = :id") fun renameFolder(id: String, name: String)
    @Query("DELETE FROM folders WHERE id = :id") fun deleteFolder(id: String)
    @Query("UPDATE folders SET position = :position WHERE id = :id") fun setPosition(id: String, position: Int)
    @Query("DELETE FROM folder_chats WHERE folder_id = :id") fun clearFolderChats(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun addFolderChats(rows: List<FolderChatEntity>)
    @Query("SELECT chat_id FROM folder_chats WHERE folder_id = :id ORDER BY chat_id") fun folderChats(id: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun block(b: BlockedEntity)
    @Query("DELETE FROM blocked WHERE account_id = :id") fun unblock(id: String)
    @Query("SELECT COUNT(*) FROM blocked WHERE account_id = :id") fun blockedCount(id: String): Int
    @Query("SELECT account_id FROM blocked ORDER BY account_id") fun blockedIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertCall(c: CallEntity)
    @Query("SELECT * FROM calls ORDER BY started_at DESC, id DESC LIMIT :limit") fun calls(limit: Int): List<CallEntity>

    @Upsert fun setKv(e: KvEntity)
    @Query("SELECT * FROM kv WHERE key = :key") fun kv(key: String): KvEntity?
    @Query("SELECT * FROM kv WHERE substr(key, 1, :len) = :prefix") fun kvPrefix(prefix: String, len: Int): List<KvEntity>
}
