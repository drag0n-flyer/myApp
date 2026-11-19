package org.example.app.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey @ColumnInfo(name = "account_id") val accountId: String,
    val username: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "is_contact") val isContact: Boolean,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Entity(tableName = "chats", indices = [Index("conversation_id", unique = true)])
data class ChatEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "is_request") val isRequest: Boolean,
    val unread: Int,
    val muted: Boolean,
    @ColumnInfo(name = "last_activity") val lastActivity: Long,
    /** Epoch milliseconds when pinned on this device; null when not pinned. Added in version 2. */
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long? = null,
    /** Added in version 2. Never changed by an incoming message. */
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    /** Disappearing-message timer, seconds; 0 is off. Added in version 3. */
    @ColumnInfo(name = "timer_seconds", defaultValue = "0") val timerSeconds: Int = 0,
    /** `direct` or `group`. Added in version 5, with the rest of the group columns. */
    @ColumnInfo(defaultValue = "'direct'") val kind: String = "direct",
    @ColumnInfo(name = "conversation_id") val conversationId: String? = null,
    val title: String? = null,
    val photo: ByteArray? = null,
    @ColumnInfo(name = "group_ver") val groupVer: Int? = null,
    @ColumnInfo(name = "members_version") val membersVersion: Long? = null,
    @ColumnInfo(name = "my_state", defaultValue = "'member'") val myState: String = "member",
)

/** A member of a group chat and their role. Added in version 5. */
@Entity(
    tableName = "group_members",
    primaryKeys = ["chat_id", "account_id"],
    foreignKeys = [ForeignKey(ChatEntity::class, ["id"], ["chat_id"], onDelete = ForeignKey.CASCADE)],
)
data class GroupMemberEntity(
    @ColumnInfo(name = "chat_id") val chatId: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    val role: String,
)

/** One member's delivered or read receipt for one message. Added in version 5. */
@Entity(
    tableName = "receipts",
    primaryKeys = ["message_id", "account_id", "kind"],
    foreignKeys = [ForeignKey(MessageEntity::class, ["id"], ["message_id"], onDelete = ForeignKey.CASCADE)],
)
data class ReceiptEntity(
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    val kind: String,
    val at: Long,
)

/**
 * One account's latest vote on a poll. No foreign key to `messages`: a vote can arrive before its poll. [optionIds] are
 * joined with U+001F (an empty string is a retracted vote). Removed with the chat. Added in version 6.
 */
@Entity(
    tableName = "poll_votes",
    primaryKeys = ["poll_id", "account_id"],
    foreignKeys = [ForeignKey(ChatEntity::class, ["id"], ["chat_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("chat_id", name = "poll_votes_chat")],
)
data class PollVoteEntity(
    @ColumnInfo(name = "poll_id") val pollId: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "chat_id") val chatId: String,
    @ColumnInfo(name = "option_ids") val optionIds: String,
    val at: Long,
)

/** Unsent composer text, one row per chat. Whitespace-only text is never stored. Added in version 2. */
@Entity(
    tableName = "drafts",
    foreignKeys = [ForeignKey(ChatEntity::class, ["id"], ["chat_id"], onDelete = ForeignKey.CASCADE)],
)
data class DraftEntity(
    @PrimaryKey @ColumnInfo(name = "chat_id") val chatId: String,
    val text: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "conversations",
    foreignKeys = [ForeignKey(ChatEntity::class, ["id"], ["chat_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("chat_id"), Index("group_id", unique = true)],
)
data class ConversationEntity(
    @PrimaryKey @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "chat_id") val chatId: String,
    @ColumnInfo(name = "group_id") val groupId: ByteArray,
    val mode: String,
    @ColumnInfo(name = "inner_mode") val innerMode: String?,
    @ColumnInfo(name = "mode_mismatch") val modeMismatch: Boolean,
    val epoch: Long,
    val active: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "members",
    primaryKeys = ["conversation_id", "identity"],
    foreignKeys = [ForeignKey(ConversationEntity::class, ["server_id"], ["conversation_id"], onDelete = ForeignKey.CASCADE)],
)
data class MemberEntity(
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val identity: String,
    @ColumnInfo(name = "signature_key") val signatureKey: ByteArray,
)

@Entity(
    tableName = "safety",
    foreignKeys = [ForeignKey(ChatEntity::class, ["id"], ["chat_id"], onDelete = ForeignKey.CASCADE)],
)
data class SafetyEntity(
    @PrimaryKey @ColumnInfo(name = "chat_id") val chatId: String,
    val current: String,
    val verified: String?,
)

/** One row per message ID: a repeated insert is ignored, which keeps processing idempotent after a crash. */
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(ChatEntity::class, ["id"], ["chat_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("chat_id", "sent_at", name = "messages_chat_sent"), Index("expires_at", name = "messages_expires")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "chat_id") val chatId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String?,
    @ColumnInfo(name = "sender_account") val senderAccount: String,
    @ColumnInfo(name = "sender_device") val senderDevice: String?,
    @ColumnInfo(name = "sent_at") val sentAt: Long,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    val envelope: ByteArray,
    val status: String,
    @ColumnInfo(name = "reply_to") val replyTo: String?,
    @ColumnInfo(name = "edited_at") val editedAt: Long?,
    val deleted: Boolean,
    val undecryptable: Boolean,
    @ColumnInfo(defaultValue = "") val folded: String,
    /** Added in version 3. */
    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null,
    val caption: String? = null,
    @ColumnInfo(name = "link_preview") val linkPreview: String? = null,
)

/** What a contact says about themselves; the highest `ver` wins. Added in version 3. */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey @ColumnInfo(name = "account_id") val accountId: String,
    val name: String,
    val photo: ByteArray?,
    val ver: Int,
)

@Entity(
    tableName = "reactions",
    primaryKeys = ["message_id", "account_id"],
    foreignKeys = [ForeignKey(MessageEntity::class, ["id"], ["message_id"], onDelete = ForeignKey.CASCADE)],
)
data class ReactionEntity(
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    val emoji: String,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(MessageEntity::class, ["id"], ["message_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("message_id", name = "attachments_message")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "message_id") val messageId: String,
    val kind: String,
    val key: ByteArray,
    val digest: ByteArray,
    val size: Long,
    @ColumnInfo(name = "cipher_size") val cipherSize: Long,
    val state: String,
    val downloaded: Long,
    @ColumnInfo(name = "local_path") val localPath: String?,
)

@Entity(tableName = "outbox", indices = [Index("chat_id", name = "outbox_chat")])
data class OutboxEntity(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "chat_id") val chatId: String,
    val envelope: ByteArray,
    val attempts: Int,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long,
    /** MLS ciphertext made for [envelope] and the epoch it was made in; null until the first encrypt. Added in version 4. */
    val ciphertext: ByteArray? = null,
    @ColumnInfo(name = "cipher_epoch") val cipherEpoch: Long? = null,
    @ColumnInfo(name = "cipher_conversation") val cipherConversation: String? = null,
)

@Entity(tableName = "inbox_raw")
data class InboxRawEntity(
    @PrimaryKey val cursor: Long,
    val kind: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val seq: Long?,
    val epoch: Long,
    @ColumnInfo(name = "sender_account") val senderAccount: String,
    @ColumnInfo(name = "sender_device") val senderDevice: String,
    val payload: ByteArray,
    @ColumnInfo(name = "sent_at") val sentAt: Long,
)

@Entity(tableName = "processed")
data class ProcessedEntity(
    @PrimaryKey @ColumnInfo(name = "payload_hash") val payloadHash: ByteArray,
    @ColumnInfo(name = "processed_at") val processedAt: Long,
)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val position: Int,
)

@Entity(
    tableName = "folder_chats",
    primaryKeys = ["folder_id", "chat_id"],
    foreignKeys = [
        ForeignKey(FolderEntity::class, ["id"], ["folder_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ChatEntity::class, ["id"], ["chat_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("chat_id", name = "folder_chats_chat")],
)
data class FolderChatEntity(
    @ColumnInfo(name = "folder_id") val folderId: String,
    @ColumnInfo(name = "chat_id") val chatId: String,
)

@Entity(tableName = "blocked")
data class BlockedEntity(
    @PrimaryKey @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "blocked_at") val blockedAt: Long,
)

@Entity(
    tableName = "calls",
    foreignKeys = [ForeignKey(ChatEntity::class, ["id"], ["chat_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("chat_id", name = "calls_chat")],
)
data class CallEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "chat_id") val chatId: String,
    val video: Boolean,
    val outgoing: Boolean,
    val outcome: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    val duration: Double,
)

@Entity(tableName = "kv")
data class KvEntity(
    @PrimaryKey val key: String,
    val value: ByteArray,
)
