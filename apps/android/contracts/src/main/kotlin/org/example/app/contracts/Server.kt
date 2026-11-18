package org.example.app.contracts

import java.io.InputStream
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

// Server wire types used across lanes (server design section 7).

class ServerException(val status: Int, val code: String, val retryAfter: Int?, val details: Map<String, Long>?) : Exception(code)

/**
 * [kind] is `message`, `commit`, `welcome` or `members`. A `members` item has an empty payload and carries
 * [membersVersion] only; the client then reads the conversation.
 */
class InboxItem(
    val cursor: Long,
    val kind: String,
    val conversationId: String,
    val seq: Long?,
    val epoch: Long,
    val senderAccountId: String,
    val senderDeviceId: String,
    val payload: ByteArray,
    val sentAt: Instant,
    val membersVersion: Long? = null,
)

/** A group's member list changed: fetch it. [membersVersion] is the server's version after the change. */
class MembersEvent(val conversationId: String, val membersVersion: Long, val actor: String? = null)

/** The `members` event of an inbox item; null for any other kind or a `members` item without a version. */
fun InboxItem.membersEvent(): MembersEvent? =
    if (kind == "members" && membersVersion != null) MembersEvent(conversationId, membersVersion, senderAccountId.takeIf { it.isNotEmpty() }) else null

sealed interface ServerEvent {
    class Inbox(val item: InboxItem) : ServerEvent
    class DeviceRevoked(val deviceId: String) : ServerEvent
    class CallRing(val callId: String, val fromAccountId: String, val fromDeviceId: String, val video: Boolean, val payload: ByteArray) : ServerEvent
    class CallAnswered(val callId: String, val deviceId: String, val payload: ByteArray) : ServerEvent
    class CallSignal(val callId: String, val fromDeviceId: String, val payload: ByteArray) : ServerEvent
    class CallEnded(val callId: String, val reason: String) : ServerEvent
    /** A typing or presence signal: an MLS application message the server cannot read, never stored. */
    class ConvEphemeral(
        val conversationId: String, val epoch: Long, val fromAccountId: String, val fromDeviceId: String, val ciphertext: ByteArray,
    ) : ServerEvent
}

enum class ConnectionState { Connecting, Connected, Disconnected, Revoked, Replaced }

interface LiveSocket {
    val events: Flow<ServerEvent>
    val states: StateFlow<ConnectionState>

    /** Returns the ack data. */
    suspend fun send(type: String, data: JsonObject): JsonObject?
}

/**
 * Sends a `conv.ephemeral` frame (design 7.3): [ciphertext] is base64url without padding, at most 4 KiB decoded. The
 * server answers with an ack, or `bad_request`, or `not_found` for a conversation the sender is not a member of.
 */
suspend fun LiveSocket.sendEphemeral(conversationId: String, epoch: Long, ciphertext: ByteArray) {
    send(
        "conv.ephemeral",
        kotlinx.serialization.json.buildJsonObject {
            put("conversationID", kotlinx.serialization.json.JsonPrimitive(conversationId))
            put("epoch", kotlinx.serialization.json.JsonPrimitive(epoch))
            put("ciphertext", kotlinx.serialization.json.JsonPrimitive(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext)))
        },
    )
}

/** Implemented by `:net`, used by `:messaging`. */
interface DeliveryApi {
    suspend fun uploadKeyPackages(keyPackages: List<ByteArray>): Int
    suspend fun keyPackageCount(): Int

    /** Maps device ID to its KeyPackage. */
    suspend fun claimKeyPackages(accountId: String, deviceIds: List<String>? = null): Map<String, ByteArray>
    suspend fun createConversation(peerAccountId: String): ConversationInfo
    /** A group conversation with the [accounts] (1 to 99 others); the caller is its owner. */
    suspend fun createGroup(accounts: List<String>): ConversationInfo = throw UnsupportedOperationException("groups")
    suspend fun conversation(id: String): ConversationInfo
    /** Owner or admin. Accounts already in are skipped. `group_full` over 100 accounts. */
    suspend fun addAccounts(id: String, accounts: List<String>): MembersChange = throw UnsupportedOperationException("groups")
    /** Removes [account], or leaves when it is the caller's own. `forbidden`, `owner_must_transfer`, `not_found`. */
    suspend fun removeAccount(id: String, account: String): MembersChange = throw UnsupportedOperationException("groups")
    /** Owner only; [role] is a [GroupRoleWire] value. */
    suspend fun setRole(id: String, account: String, role: String): MembersChange = throw UnsupportedOperationException("groups")
    suspend fun postCommit(id: String, epoch: Long, commit: ByteArray, welcome: ByteArray?, addDevices: List<String>, removeDevices: List<String>): Long
    suspend fun postMessage(id: String, epoch: Long, ciphertext: ByteArray, escrow: ByteArray?, clientMsgId: String): Long

    /**
     * Like the five-argument form, with the time (epoch milliseconds) after which the server may delete undelivered
     * copies (`expiresAt`, a disappearing message). The default ignores it, for implementations that predate it.
     */
    suspend fun postMessage(
        id: String, epoch: Long, ciphertext: ByteArray, escrow: ByteArray?, clientMsgId: String, expiresAtMs: Long?,
    ): Long = postMessage(id, epoch, ciphertext, escrow, clientMsgId)
    suspend fun inbox(after: Long?, limit: Int = 100): InboxPage
    suspend fun ack(upTo: Long)
    suspend fun devices(): List<DeviceRecord>
    suspend fun uploadAttachment(bytes: InputStream, length: Long): AttachmentRef
    suspend fun downloadAttachment(id: String, fromByte: Long): InputStream
}

/**
 * [mode] is `e2ee` or `server_readable`. [kind] is `direct` (members empty, no version) or `group`, with the server's
 * member list and its [membersVersion] from one snapshot.
 */
class ConversationInfo(
    val conversationId: String, val mode: String, val epoch: Long,
    val kind: String = "direct", val members: List<GroupMemberInfo> = emptyList(), val membersVersion: Long? = null,
)

/** Role names on the wire. */
object GroupRoleWire {
    const val OWNER = "owner"
    const val ADMIN = "admin"
    const val MEMBER = "member"
}

/** One member of a group as the server lists it. [role] is a [GroupRoleWire] value. */
class GroupMemberInfo(val account: String, val role: String)

/** The answer of a membership change: the new member list version, and whether the group is gone (the last member left). */
class MembersChange(val membersVersion: Long, val deleted: Boolean)

class InboxPage(val items: List<InboxItem>, val more: Boolean)

class DeviceRecord(
    val id: String,
    val name: String,
    val platform: String,
    val createdAt: Instant,
    val lastSeenAt: Instant?,
    val current: Boolean,
)

class AttachmentRef(val id: String, val expiresAt: Instant)
