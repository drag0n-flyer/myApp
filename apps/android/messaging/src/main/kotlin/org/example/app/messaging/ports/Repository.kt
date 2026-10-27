package org.example.app.messaging.ports

/*
 * Storage port. `:store` implements it on Room and SQLCipher; tests use an in-memory fake.
 * Mirrors the iPhone LocalStore. Times are epoch milliseconds. Calls block: run them off the main thread.
 * Nothing here may log payloads, envelopes or keys.
 */

data class ContactRecord(val accountId: String, val username: String, val displayName: String, val isContact: Boolean, val addedAt: Long)

data class ChatRecord(val id: String, val isRequest: Boolean, val unread: Int, val muted: Boolean, val lastActivity: Long)

data class ConversationRecord(
    val serverId: String,
    val chatId: String,
    val groupId: ByteArray,
    /** The server's claim: `e2ee` or `server_readable`. */
    val mode: String,
    /** Pinned from the creator's `mode` envelope. */
    val innerMode: String?,
    val modeMismatch: Boolean,
    val epoch: Long,
    val active: Boolean,
    val createdAt: Long,
) {
    override fun equals(other: Any?) = other is ConversationRecord && serverId == other.serverId && chatId == other.chatId &&
        groupId.contentEquals(other.groupId) && mode == other.mode && innerMode == other.innerMode &&
        modeMismatch == other.modeMismatch && epoch == other.epoch && active == other.active && createdAt == other.createdAt
    override fun hashCode() = serverId.hashCode()
}

data class MemberRecord(val identity: String, val signatureKey: ByteArray) {
    override fun equals(other: Any?) = other is MemberRecord && identity == other.identity && signatureKey.contentEquals(other.signatureKey)
    override fun hashCode() = identity.hashCode()
}

data class SafetyRecord(val current: String, val verified: String?)

data class MessageRecord(
    val id: String,
    val chatId: String,
    val conversationId: String?,
    val senderAccount: String,
    val senderDevice: String?,
    val sentAt: Long,
    val receivedAt: Long,
    val envelope: ByteArray,
    val status: String,
    val replyTo: String? = null,
    val editedAt: Long? = null,
    val deleted: Boolean = false,
    val undecryptable: Boolean = false,
    val folded: String = "",
) {
    override fun equals(other: Any?) = other is MessageRecord && id == other.id && chatId == other.chatId &&
        conversationId == other.conversationId && senderAccount == other.senderAccount && senderDevice == other.senderDevice &&
        sentAt == other.sentAt && receivedAt == other.receivedAt && envelope.contentEquals(other.envelope) &&
        status == other.status && replyTo == other.replyTo && editedAt == other.editedAt && deleted == other.deleted &&
        undecryptable == other.undecryptable && folded == other.folded
    override fun hashCode() = id.hashCode()
}

data class ReactionRecord(val messageId: String, val accountId: String, val emoji: String)

data class AttachmentRecord(
    val id: String,
    val messageId: String,
    val kind: String,
    val key: ByteArray,
    val digest: ByteArray,
    val size: Long,
    val cipherSize: Long,
    /** `remote`, `downloading`, `local` or `failed`. */
    val state: String,
    val downloaded: Long,
    val localPath: String?,
) {
    override fun equals(other: Any?) = other is AttachmentRecord && id == other.id && messageId == other.messageId &&
        kind == other.kind && key.contentEquals(other.key) && digest.contentEquals(other.digest) && size == other.size &&
        cipherSize == other.cipherSize && state == other.state && downloaded == other.downloaded && localPath == other.localPath
    override fun hashCode() = id.hashCode()
}

data class OutboxRecord(val messageId: String, val chatId: String, val envelope: ByteArray, val attempts: Int, val nextAttemptAt: Long) {
    override fun equals(other: Any?) = other is OutboxRecord && messageId == other.messageId && chatId == other.chatId &&
        envelope.contentEquals(other.envelope) && attempts == other.attempts && nextAttemptAt == other.nextAttemptAt
    override fun hashCode() = messageId.hashCode()
}

data class RawInboxRecord(
    val cursor: Long,
    val kind: String,
    val conversationId: String,
    val seq: Long?,
    val epoch: Long,
    val senderAccount: String,
    val senderDevice: String,
    val payload: ByteArray,
    val sentAt: Long,
) {
    override fun equals(other: Any?) = other is RawInboxRecord && cursor == other.cursor && kind == other.kind &&
        conversationId == other.conversationId && seq == other.seq && epoch == other.epoch &&
        senderAccount == other.senderAccount && senderDevice == other.senderDevice &&
        payload.contentEquals(other.payload) && sentAt == other.sentAt
    override fun hashCode() = cursor.hashCode()
}

data class FolderRecord(val id: String, val name: String, val position: Int)

data class CallRecord(
    val id: String,
    val chatId: String,
    val video: Boolean,
    val outgoing: Boolean,
    val outcome: String,
    val startedAt: Long,
    val durationSeconds: Double,
)

data class SearchHit(val chatId: String, val messageId: String)

/** Allowed values of `ConversationRecord.mode` and `innerMode`. The iPhone enforces the same set with CHECK. */
object ConversationMode {
    const val E2EE = "e2ee"
    const val SERVER_READABLE = "server_readable"
    val all = setOf(E2EE, SERVER_READABLE)
}

/** Allowed values of `AttachmentRecord.state`. */
object AttachmentState {
    const val REMOTE = "remote"
    const val DOWNLOADING = "downloading"
    const val LOCAL = "local"
    const val FAILED = "failed"
    val all = setOf(REMOTE, DOWNLOADING, LOCAL, FAILED)
}

interface Repository {
    /** Runs [body] in one database transaction; an exception rolls everything back. */
    fun <T> transaction(body: () -> T): T

    // contacts
    fun upsertContact(c: ContactRecord)
    fun contact(id: String): ContactRecord?
    fun contacts(): List<ContactRecord>

    // chats
    fun upsertChat(c: ChatRecord)
    fun chat(id: String): ChatRecord?
    fun chats(): List<ChatRecord>
    fun setUnread(chatId: String, n: Int)
    fun setRequest(chatId: String, value: Boolean)
    /** Deletes the chat and, by cascade, its conversations, messages and the rest. */
    fun deleteChat(id: String)

    // conversations
    /** @throws IllegalArgumentException when `mode` or `innerMode` is not a [ConversationMode] value. */
    fun upsertConversation(c: ConversationRecord)
    fun conversations(chatId: String): List<ConversationRecord>
    fun conversation(serverId: String): ConversationRecord?
    fun conversationByGroup(groupId: ByteArray): ConversationRecord?
    /** The active conversation with the smallest server ID. */
    fun preferredConversation(chatId: String): ConversationRecord?
    fun setEpoch(serverId: String, epoch: Long)
    /** Pins `inner_mode` only while it is null. A different pinned value is kept, `mode_mismatch` is set and false is returned. */
    fun pinInnerMode(serverId: String, mode: String): Boolean
    fun setServerMode(serverId: String, mode: String)
    fun flagModeMismatch(serverId: String)

    // members and safety
    fun members(conversationId: String): List<MemberRecord>
    fun replaceMembers(conversationId: String, members: List<MemberRecord>)
    fun safety(chatId: String): SafetyRecord?
    fun setSafetyCurrent(chatId: String, current: String)
    fun setVerified(chatId: String, verified: String?)

    // messages
    /** Returns true when inserted, false when a message with this ID already exists (idempotent). */
    fun insertMessage(m: MessageRecord): Boolean
    fun message(id: String): MessageRecord?
    /** The newest [limit] messages older than [before] (all when null), oldest first. */
    fun messages(chatId: String, before: MessageRecord?, limit: Int): List<MessageRecord>
    /** Never lowers a status along sending < sent < delivered < read. Other targets are set unconditionally. */
    fun updateStatus(ids: List<String>, status: String)
    fun applyEdit(id: String, sender: String, envelope: ByteArray, folded: String, at: Long): Boolean
    fun markDeleted(id: String, sender: String): Boolean
    fun setReaction(messageId: String, accountId: String, emoji: String)
    fun reactions(messageId: String): List<ReactionRecord>
    /** Searches folded text. Fewer than 3 characters use LIKE; longer queries use the trigram index. */
    fun search(query: String, limit: Int): List<SearchHit>

    // attachments
    fun upsertAttachment(a: AttachmentRecord)
    fun attachment(id: String): AttachmentRecord?
    fun attachmentForMessage(messageId: String): AttachmentRecord?
    fun setAttachmentState(id: String, state: String, downloaded: Long, localPath: String?)

    // outbox
    fun enqueue(o: OutboxRecord)
    fun enqueueSend(message: MessageRecord, outbox: OutboxRecord)
    fun dueOutbox(at: Long): List<OutboxRecord>
    /** The oldest row of each chat, when due. A newer message never overtakes an older one that is waiting. */
    fun dueOutboxHeads(at: Long): List<OutboxRecord>
    fun completeSend(id: String, status: String)
    fun requeueSend(id: String, now: Long): Boolean
    fun bumpOutbox(id: String, nextAttemptAt: Long)
    fun removeOutbox(id: String)

    // raw inbox and processed hashes
    fun insertRaw(items: List<RawInboxRecord>)
    fun rawItems(limit: Int): List<RawInboxRecord>
    fun deleteRaw(cursor: Long)
    /** [hash] is the SHA-256 of the payload (see `PayloadHash`). */
    /** Returns true when the hash is new, false when it was already recorded (dedupe in one step). */
    fun markProcessed(hash: ByteArray, at: Long): Boolean
    fun isProcessed(hash: ByteArray): Boolean
    fun pruneProcessed(olderThan: Long)

    // folders
    fun folders(): List<FolderRecord>
    fun addFolder(f: FolderRecord)
    fun renameFolder(id: String, name: String)
    fun deleteFolder(id: String)
    fun moveFolder(id: String, to: Int)
    fun setFolderChats(folderId: String, chatIds: List<String>)
    fun folderChats(folderId: String): List<String>

    // blocked
    fun block(accountId: String, at: Long)
    fun unblock(accountId: String)
    fun isBlocked(accountId: String): Boolean
    fun blockedIds(): List<String>

    // calls
    fun insertCall(c: CallRecord)
    fun calls(limit: Int): List<CallRecord>

    // key-value
    fun get(key: String): ByteArray?
    fun set(key: String, value: ByteArray)
    fun entries(prefix: String): Map<String, ByteArray>

    /** Deletes every row in every table and truncates the write-ahead log (sign-out, reset). */
    fun wipe()
}

/** Key of the `processed` table: SHA-256 of the received payload. The same ciphertext is never processed twice. */
object PayloadHash {
    fun of(payload: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
}
