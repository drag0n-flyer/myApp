package org.example.app.messaging.ports

/*
 * Storage port. `:store` implements it on Room and SQLCipher; tests use an in-memory fake.
 * Mirrors the iPhone LocalStore. Times are epoch milliseconds. Calls block: run them off the main thread.
 * Nothing here may log payloads, envelopes or keys.
 */

data class ContactRecord(val accountId: String, val username: String, val displayName: String, val isContact: Boolean, val addedAt: Long)

data class ChatRecord(
    val id: String,
    val isRequest: Boolean,
    val unread: Int,
    val muted: Boolean,
    val lastActivity: Long,
    /** When the chat was pinned on this device; null when not pinned. */
    val pinnedAt: Long? = null,
    val archived: Boolean = false,
    /** Disappearing-message timer for the chat in seconds; 0 is off. */
    val timerSeconds: Int = 0,
    /** [ChatKind.DIRECT] (the chat ID is the peer's account ID) or [ChatKind.GROUP] (the ID is `g:<conversationID>`). */
    val kind: String = ChatKind.DIRECT,
    /** A group's conversation ID; null for a one-to-one chat. */
    val conversationId: String? = null,
    /** A group's name; null until it has one. */
    val title: String? = null,
    /** A group's photo (JPEG, at most 24,576 bytes). */
    val photo: ByteArray? = null,
    /** The `ver` of the stored group name and photo; null before the first. */
    val groupVer: Int? = null,
    /** The server's member list version this copy of the list is at; null before the first list. */
    val membersVersion: Long? = null,
    /** [MyState.MEMBER], [MyState.REMOVED] or [MyState.LEFT]. */
    val myState: String = MyState.MEMBER,
) {
    override fun equals(other: Any?) = other is ChatRecord && id == other.id && isRequest == other.isRequest && unread == other.unread &&
        muted == other.muted && lastActivity == other.lastActivity && pinnedAt == other.pinnedAt && archived == other.archived &&
        timerSeconds == other.timerSeconds && kind == other.kind && conversationId == other.conversationId && title == other.title &&
        (photo contentEquals other.photo) && groupVer == other.groupVer && membersVersion == other.membersVersion && myState == other.myState
    override fun hashCode() = id.hashCode()
}

/** Allowed values of [ChatRecord.kind]. */
object ChatKind {
    const val DIRECT = "direct"
    const val GROUP = "group"
}

/** Allowed values of [ChatRecord.myState]: whether this account is still in the group. */
object MyState {
    const val MEMBER = "member"
    const val REMOVED = "removed"
    const val LEFT = "left"
    val all = setOf(MEMBER, REMOVED, LEFT)
}

/** One member of a group chat; [role] is a `GroupRole` wire name (`owner`, `admin`, `member`). */
data class GroupMemberRecord(val accountId: String, val role: String)

/** A delivered or read receipt from one member for one of my messages. [kind] is `delivered` or `read`. */
data class ReceiptRecord(val messageId: String, val accountId: String, val kind: String, val at: Long)

/** What a store must refuse to keep, whatever it was sent: the same checks in every [Repository]. */
object GroupStoreRules {
    const val MAX_MEMBERS = 100
    const val PHOTO_MAX = 24_576
    /** A JPEG frame header alone is longer than this. */
    const val PHOTO_MIN = 4
    val receiptKinds = setOf("delivered", "read")

    /** Throws [IllegalArgumentException] for a list that is too long, has a repeated or empty account ID, or an unknown role. */
    fun requireMembers(members: List<GroupMemberRecord>, version: Long) {
        require(version >= 0) { "Negative members version" }
        require(members.size <= MAX_MEMBERS) { "Too many members" }
        require(members.map { it.accountId }.toSet().size == members.size) { "Repeated member" }
        for (m in members) {
            require(m.accountId.isNotEmpty()) { "Empty account ID" }
            require(org.example.app.core.groups.GroupRole.from(m.role) != null) { "Unknown role" }
        }
    }

    private val conversationIdRule = Regex("[A-Za-z0-9_-]+")

    /** A conversation ID of letters, digits, `_` and `-` only; a title, when given, that the name rule returns unchanged. */
    fun requireGroup(conversationId: String, title: String?) {
        require(conversationIdRule.matches(conversationId)) { "Bad conversation ID" }
        if (title != null) require(org.example.app.core.groups.GroupNameRule.clean(title) == title) { "Bad group name" }
    }

    /** The name must pass the group name rule as it is; a photo is a JPEG of at most 24,576 bytes. */
    fun requireInfo(title: String, photo: ByteArray?) {
        require(org.example.app.core.groups.GroupNameRule.clean(title) == title) { "Bad group name" }
        if (photo != null) require(org.example.app.core.people.ProfilePhotoRule.isAcceptable(photo)) { "Bad group photo" }
    }
}

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
    /** When the message disappears (epoch ms), from `at + ttl`; null for none. */
    val expiresAt: Long? = null,
    /** The caption of a photo or file, as sent. Its folded text is part of [folded]. */
    val caption: String? = null,
    /** The link preview (`lp`) as JSON, when the text carried a valid one. */
    val linkPreview: String? = null,
) {
    override fun equals(other: Any?) = other is MessageRecord && id == other.id && chatId == other.chatId &&
        conversationId == other.conversationId && senderAccount == other.senderAccount && senderDevice == other.senderDevice &&
        sentAt == other.sentAt && receivedAt == other.receivedAt && envelope.contentEquals(other.envelope) &&
        status == other.status && replyTo == other.replyTo && editedAt == other.editedAt && deleted == other.deleted &&
        undecryptable == other.undecryptable && folded == other.folded &&
        expiresAt == other.expiresAt && caption == other.caption && linkPreview == other.linkPreview
    override fun hashCode() = id.hashCode()
}

/** What a contact says about themselves (`profile` envelope). [ver] only ever rises. */
class ProfileRecord(val accountId: String, val name: String, val photo: ByteArray?, val ver: Int) {
    override fun equals(other: Any?) = other is ProfileRecord && accountId == other.accountId && name == other.name &&
        (photo contentEquals other.photo) && ver == other.ver
    override fun hashCode() = accountId.hashCode()
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

/**
 * One queued send. [ciphertext] and [cipherEpoch] hold the MLS ciphertext made for this row's envelope and the epoch it
 * was made in, so a retry in the same epoch posts the same bytes instead of encrypting (and advancing the sender's
 * generation) again. Both are null until the first encrypt; the row is replaced (so they are cleared) with its envelope.
 */
data class OutboxRecord(
    val messageId: String, val chatId: String, val envelope: ByteArray, val attempts: Int, val nextAttemptAt: Long,
    val ciphertext: ByteArray? = null, val cipherEpoch: Long? = null, val cipherConversation: String? = null,
) {
    override fun equals(other: Any?) = other is OutboxRecord && messageId == other.messageId && chatId == other.chatId &&
        envelope.contentEquals(other.envelope) && attempts == other.attempts && nextAttemptAt == other.nextAttemptAt &&
        (ciphertext?.contentEquals(other.ciphertext) ?: (other.ciphertext == null)) && cipherEpoch == other.cipherEpoch &&
        cipherConversation == other.cipherConversation
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
    /** Raises `last_activity` to [at] if it is older. One column, so a pin, mute or archive made meanwhile is never undone. */
    fun bumpActivity(chatId: String, at: Long)
    /** Lowers unread by [n], never below zero, in one statement (a read receipt from another of my devices). */
    fun reduceUnread(chatId: String, n: Int)
    /** An incoming message: adds [unreadDelta] to unread and raises `last_activity` to [at] if older. One statement. */
    fun bumpChat(chatId: String, unreadDelta: Int, at: Long)
    /** Local only. Each of these changes one column and nothing else, so an incoming message cannot undo it. */
    fun setPinnedAt(chatId: String, at: Long?)
    fun setMuted(chatId: String, muted: Boolean)
    fun setArchived(chatId: String, archived: Boolean)
    /** The saved composer text, or null. */
    fun draft(chatId: String): String?
    /** Every saved draft, by chat ID. */
    fun drafts(): Map<String, String>
    /** Saves [text] for the chat; blank or null removes the draft. Ignored for a chat that does not exist. */
    fun setDraft(chatId: String, text: String?, at: Long)
    /** Local and replicated by the `timer` envelope. Changes one column. */
    fun setTimer(chatId: String, seconds: Int)
    /** 0 (off) for an unknown chat. */
    fun timerSeconds(chatId: String): Int
    /**
     * Removes every message whose `expires_at` is at or before [now], in one transaction: the row, its reactions, its
     * attachment records, its outbox row and (by trigger) its search entry; then deletes the attachment files. Returns
     * the removed message IDs so their notifications can be cancelled. A removed message that was still unread (not from
     * [ownAccount] or `system`, not `read`) lowers its chat's unread count, never below zero.
     */
    fun sweepExpired(now: Long, ownAccount: String): List<String>

    /**
     * Per chat, how many unread incoming messages (not from [ownAccount] or `system`, not `read`) have already
     * expired at [now] but are not swept yet; the chat list subtracts them from the stored unread count.
     */
    fun expiredUnread(now: Long, ownAccount: String): Map<String, Int>

    /** The IDs of the messages whose `expires_at` is at or before [now], not yet swept. */
    fun expiredMessageIds(now: Long): List<String>

    // profiles
    fun profile(accountId: String): ProfileRecord?
    /** Saves only when [ver] is strictly higher than the stored one (`ProfileMerge`). Returns whether it saved. */
    fun saveProfile(accountId: String, name: String, photo: ByteArray?, ver: Int): Boolean

    /** Deletes the chat and, by cascade, its conversations, messages and the rest. */
    fun deleteChat(id: String)

    // groups
    /**
     * Makes the group chat [chatId] (`g:<conversationID>`) for [conversationId] when there is none: not a request, nothing
     * unread, activity [now]. An existing group chat keeps everything (a pin, mute or title) and only gets the conversation ID.
     * @throws IllegalArgumentException when [chatId] is an existing one-to-one chat, or another chat already holds [conversationId].
     */
    fun upsertGroup(chatId: String, conversationId: String, title: String?, now: Long)
    /**
     * Replaces the group's member list and records [version], in one transaction, and returns the list it replaced. Null,
     * and nothing changed, when [version] is not higher than the stored one or the chat is not a group chat.
     * @throws IllegalArgumentException for a list that fails [GroupStoreRules.requireMembers].
     */
    fun setGroupMembers(chatId: String, members: List<GroupMemberRecord>, version: Long): List<GroupMemberRecord>?
    /** The stored member list, by account ID. Empty for a one-to-one chat. */
    fun groupMembers(chatId: String): List<GroupMemberRecord>
    /**
     * Keeps a group's name and photo when [ver] is at least 1 and strictly higher than the stored one. False, and nothing
     * changed, otherwise or for a chat that is not a group.
     * @throws IllegalArgumentException for a name or photo that fails [GroupStoreRules.requireInfo].
     */
    fun setGroupInfo(chatId: String, title: String, photo: ByteArray?, ver: Int): Boolean
    /** One column. @throws IllegalArgumentException for a value outside [MyState.all]. */
    fun setMyState(chatId: String, state: String)
    /**
     * Stores one receipt. True when new; false, and the first one stays, when this member already has one of this kind
     * for the message, and false for a message that does not exist.
     * @throws IllegalArgumentException for a kind other than `delivered` or `read`.
     */
    fun addReceipt(messageId: String, accountId: String, kind: String, at: Long): Boolean
    fun receipts(messageId: String): List<ReceiptRecord>

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
    /**
     * Applies an edit: new envelope, search text and edit time. [caption] and [linkPreview] are the new values of those
     * columns, stored as given (null removes the caption or preview; a caption can be added where there was none).
     */
    fun applyEdit(id: String, sender: String, envelope: ByteArray, folded: String, at: Long, caption: String?, linkPreview: String?): Boolean
    /**
     * Replaces the envelope of one's own message that has not left yet (`sending` or `failed`), without marking it edited.
     * An attachment is stored before its upload, with what is known then; this puts in what the upload returned.
     */
    /**
     * Removes one's own message that has not left yet (`sending` or `failed`) together with its outbox row: an attachment
     * that can never be sent. False, and nothing removed, for any other message.
     */
    fun deleteUnsentMessage(id: String, sender: String): Boolean
    fun replaceUnsentEnvelope(id: String, sender: String, envelope: ByteArray, folded: String, receivedAt: Long): Boolean
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
    /** Keeps the ciphertext made for the outbox row [id] and its epoch. Does nothing when the row is gone. */
    fun storeCiphertext(id: String, conversationId: String, epoch: Long, ciphertext: ByteArray)

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
