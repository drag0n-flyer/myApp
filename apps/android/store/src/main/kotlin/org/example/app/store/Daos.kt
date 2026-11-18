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
    @Query("UPDATE chats SET unread = MAX(0, unread - :n) WHERE id = :id") fun reduceUnread(id: String, n: Int)
    @Query("UPDATE chats SET is_request = :v WHERE id = :id") fun setRequest(id: String, v: Boolean)
    @Query("UPDATE chats SET pinned_at = :at WHERE id = :id") fun setPinnedAt(id: String, at: Long?)
    @Query("UPDATE chats SET muted = :v WHERE id = :id") fun setMuted(id: String, v: Boolean)
    /** Archiving also unpins, in the same statement; unarchiving leaves the pin as it is (none). */
    @Query("UPDATE chats SET archived = :v, pinned_at = CASE WHEN :v THEN NULL ELSE pinned_at END WHERE id = :id")
    fun setArchived(id: String, v: Boolean)
    @Query("UPDATE chats SET last_activity = :at WHERE id = :id AND last_activity < :at") fun bumpActivity(id: String, at: Long)
    @Query("UPDATE chats SET unread = unread + :delta, last_activity = MAX(last_activity, :at) WHERE id = :id")
    fun bumpChat(id: String, delta: Int, at: Long)
    @Query("SELECT text FROM drafts WHERE chat_id = :id") fun draft(id: String): String?
    @Query("SELECT chat_id, text FROM drafts ORDER BY chat_id") fun drafts(): List<DraftRow>
    /** Only for a chat that exists; a draft for no chat is dropped rather than failing the foreign key. */
    @Query("INSERT OR REPLACE INTO drafts(chat_id, text, updated_at) SELECT id, :text, :at FROM chats WHERE id = :id")
    fun putDraft(id: String, text: String, at: Long)
    @Query("DELETE FROM drafts WHERE chat_id = :id") fun deleteDraft(id: String)
    @Query("UPDATE chats SET timer_seconds = :seconds WHERE id = :id") fun setTimer(id: String, seconds: Int)
    @Query("SELECT timer_seconds FROM chats WHERE id = :id") fun timerSeconds(id: String): Int?
    @Query("DELETE FROM outbox WHERE chat_id = :id") fun deleteOutbox(id: String)
    @Query("DELETE FROM chats WHERE id = :id") fun delete(id: String)

    // Groups (version 5)
    /** Only for a chat that does not exist yet; an existing row is never replaced. */
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertNew(c: ChatEntity)
    @Query("UPDATE chats SET conversation_id = :conversationId WHERE id = :id") fun setConversationId(id: String, conversationId: String)
    @Query("SELECT * FROM group_members WHERE chat_id = :id ORDER BY account_id") fun members(id: String): List<GroupMemberEntity>
    @Query("DELETE FROM group_members WHERE chat_id = :id") fun clearMembers(id: String)
    @Insert fun insertMembers(m: List<GroupMemberEntity>)
    @Query("UPDATE chats SET members_version = :version WHERE id = :id") fun setMembersVersion(id: String, version: Long)
    @Query("UPDATE chats SET title = :title, photo = :photo, group_ver = :ver WHERE id = :id") fun setInfo(id: String, title: String, photo: ByteArray?, ver: Int)
    @Query("UPDATE chats SET my_state = :state WHERE id = :id") fun setMyState(id: String, state: String)
    @Query("SELECT COUNT(*) FROM messages WHERE id = :messageId") fun messageCount(messageId: String): Int
    @Query("SELECT COUNT(*) FROM receipts WHERE message_id = :messageId AND account_id = :accountId AND kind = :kind")
    fun receiptCount(messageId: String, accountId: String, kind: String): Int
    /** Never over an existing receipt: the first one stays. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertReceipt(r: ReceiptEntity)
    @Query("SELECT * FROM receipts WHERE message_id = :messageId ORDER BY account_id, kind") fun receipts(messageId: String): List<ReceiptEntity>
}

data class DraftRow(
    @androidx.room.ColumnInfo(name = "chat_id") val chatId: String,
    @androidx.room.ColumnInfo(name = "text") val text: String,
)

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
    @Query("SELECT * FROM messages WHERE chat_id = :chatId AND (expires_at IS NULL OR expires_at > :now) ORDER BY sent_at DESC, id DESC LIMIT :limit")
    fun newest(chatId: String, now: Long, limit: Int): List<MessageEntity>
    @Query(
        "SELECT * FROM messages WHERE chat_id = :chatId AND (sent_at < :sentAt OR (sent_at = :sentAt AND id < :id)) " +
            "AND (expires_at IS NULL OR expires_at > :now) ORDER BY sent_at DESC, id DESC LIMIT :limit",
    )
    fun olderThan(chatId: String, sentAt: Long, id: String, now: Long, limit: Int): List<MessageEntity>
    @Query(
        "UPDATE messages SET status = :status WHERE id IN (:ids) AND " +
            "CASE status WHEN 'sending' THEN 0 WHEN 'sent' THEN 1 WHEN 'delivered' THEN 2 WHEN 'read' THEN 3 ELSE -1 END < :rank",
    )
    fun raiseStatus(ids: List<String>, status: String, rank: Int)
    @Query("UPDATE messages SET status = :status WHERE id IN (:ids)") fun forceStatus(ids: List<String>, status: String)
    @Query(
        "UPDATE messages SET envelope = :envelope, folded = :folded, edited_at = :at, " +
            "caption = :caption, " +
            "link_preview = :linkPreview " +
            "WHERE id = :id AND sender_account = :sender AND deleted = 0",
    )
    fun applyEdit(id: String, sender: String, envelope: ByteArray, folded: String, at: Long, caption: String?, linkPreview: String?): Int
    @Query("UPDATE messages SET envelope = :envelope, folded = :folded, received_at = :receivedAt WHERE id = :id AND sender_account = :sender AND deleted = 0 AND status IN ('sending', 'failed')")
    fun replaceUnsentEnvelope(id: String, sender: String, envelope: ByteArray, folded: String, receivedAt: Long): Int
    @Query("DELETE FROM messages WHERE id = :id AND sender_account = :sender AND status IN ('sending', 'failed')")
    fun deleteUnsent(id: String, sender: String): Int
    @Query("UPDATE messages SET deleted = 1, envelope = x'', folded = '' WHERE id = :id AND sender_account = :sender")
    fun markDeleted(id: String, sender: String): Int
    @Query("UPDATE messages SET status = 'sending', received_at = :now WHERE id = :id AND status = 'failed'") fun resetForRetry(id: String, now: Long): Int

    @Query("SELECT id FROM messages WHERE expires_at IS NOT NULL AND expires_at <= :now") fun expiredIds(now: Long): List<String>
    @Query(
        "SELECT chat_id, COUNT(*) AS n FROM messages WHERE id IN (:ids) AND sender_account NOT IN (:own, 'system') " +
            "AND status != 'read' GROUP BY chat_id",
    )
    fun unreadAmong(ids: List<String>, own: String): List<UnreadRow>
    @Query("SELECT local_path FROM attachments WHERE message_id IN (:ids) AND local_path IS NOT NULL") fun pathsFor(ids: List<String>): List<String>
    @Query("DELETE FROM messages WHERE id IN (:ids)") fun deleteByIds(ids: List<String>)
    @Query("SELECT * FROM profiles WHERE account_id = :id") fun profile(id: String): ProfileEntity?
    @Query("SELECT ver FROM profiles WHERE account_id = :id") fun profileVer(id: String): Int?
    @Upsert fun putProfile(p: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun setReaction(r: ReactionEntity)
    @Query("DELETE FROM reactions WHERE message_id = :messageId AND account_id = :accountId") fun removeReaction(messageId: String, accountId: String)
    @Query("SELECT * FROM reactions WHERE message_id = :messageId ORDER BY account_id") fun reactions(messageId: String): List<ReactionEntity>

    @Query("SELECT chat_id, id FROM messages WHERE deleted = 0 AND (expires_at IS NULL OR expires_at > :now) AND folded LIKE :pattern ESCAPE '\\' ORDER BY sent_at DESC LIMIT :limit")
    fun searchLike(pattern: String, now: Long, limit: Int): List<SearchRow>

    @Upsert fun upsertAttachment(a: AttachmentEntity)
    @Query(
        "SELECT * FROM attachments WHERE id = :id AND NOT EXISTS " +
            "(SELECT 1 FROM messages m WHERE m.id = attachments.message_id AND m.expires_at <= :now)",
    )
    fun attachment(id: String, now: Long): AttachmentEntity?
    @Query(
        "SELECT * FROM attachments WHERE message_id = :messageId AND NOT EXISTS " +
            "(SELECT 1 FROM messages m WHERE m.id = attachments.message_id AND m.expires_at <= :now) ORDER BY id LIMIT 1",
    )
    fun attachmentForMessage(messageId: String, now: Long): AttachmentEntity?
    @Query(
        "SELECT chat_id, COUNT(*) AS n FROM messages WHERE expires_at <= :now AND sender_account NOT IN (:own, 'system') " +
            "AND status != 'read' GROUP BY chat_id",
    )
    fun expiredUnread(now: Long, own: String): List<UnreadRow>
    @Query("UPDATE attachments SET state = :state, downloaded = :downloaded, local_path = :path WHERE id = :id")
    fun setAttachmentState(id: String, state: String, downloaded: Long, path: String?)
}

data class UnreadRow(
    @androidx.room.ColumnInfo(name = "chat_id") val chatId: String,
    @androidx.room.ColumnInfo(name = "n") val n: Int,
)

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
    @Query("UPDATE outbox SET ciphertext = :ciphertext, cipher_epoch = :epoch, cipher_conversation = :conversationId WHERE message_id = :id")
    fun storeCiphertext(id: String, conversationId: String, epoch: Long, ciphertext: ByteArray)
    @Query("UPDATE outbox SET ciphertext = NULL, cipher_epoch = NULL, cipher_conversation = NULL WHERE message_id = :id") fun clearCiphertext(id: String)
    @Query("DELETE FROM outbox WHERE message_id IN (:ids)") fun removeAll(ids: List<String>)

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
