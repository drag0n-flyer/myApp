package org.example.app.net.api

import java.io.InputStream
import kotlinx.serialization.json.JsonObject
import org.example.app.contracts.AttachmentRef
import org.example.app.contracts.ConversationInfo
import org.example.app.contracts.DeliveryApi
import org.example.app.contracts.DeviceRecord
import org.example.app.contracts.GroupMemberInfo
import org.example.app.contracts.GroupRoleWire
import org.example.app.contracts.MembersChange
import org.example.app.contracts.ServerException
import org.example.app.contracts.InboxItem
import org.example.app.contracts.InboxPage
import org.example.app.net.Auth
import org.example.app.net.ServerClient
import org.example.app.net.models.*

/** Server design section 7.2 (plus devices and attachments, which `:messaging` reaches through [DeliveryApi]). */
class DeliveryApiImpl(
    private val client: ServerClient,
    private val attachments: AttachmentsApi = AttachmentsApi(client),
) : DeliveryApi {
    override suspend fun uploadKeyPackages(keyPackages: List<ByteArray>): Int =
        client.request("POST", "/v1/keypackages", encodeBody(UploadKeyPackagesBody(keyPackages)), Auth.Session, CountWire.serializer()).count

    override suspend fun keyPackageCount(): Int =
        client.request("GET", "/v1/keypackages/count", null, Auth.Session, CountWire.serializer()).count

    override suspend fun claimKeyPackages(accountId: String, deviceIds: List<String>?): Map<String, ByteArray> {
        val body = ClaimBody(accountId, deviceIds?.takeIf { it.isNotEmpty() })
        return client.request("POST", "/v1/keypackages/claim", encodeBody(body), Auth.Session, ClaimResponse.serializer())
            .keyPackages.associate { it.deviceID to it.keyPackage }
    }

    override suspend fun createConversation(peerAccountId: String): ConversationInfo =
        client.request("POST", "/v1/conversations", encodeBody(CreateConversationBody(listOf(peerAccountId))), Auth.Session, ConversationWire.serializer())
            .toInfo()

    override suspend fun createGroup(accounts: List<String>): ConversationInfo =
        client.request("POST", "/v1/conversations", encodeBody(CreateGroupBody("group", accounts)), Auth.Session, ConversationWire.serializer())
            .toInfo()

    override suspend fun addAccounts(id: String, accounts: List<String>): MembersChange =
        client.request("POST", "/v1/conversations/${seg(id)}/accounts", encodeBody(AccountsBody(accounts)), Auth.Session, MembersChangeWire.serializer())
            .toChange()

    override suspend fun removeAccount(id: String, account: String): MembersChange =
        client.request("DELETE", "/v1/conversations/${seg(id)}/accounts/${seg(account)}", null, Auth.Session, MembersChangeWire.serializer())
            .toChange()

    override suspend fun setRole(id: String, account: String, role: String): MembersChange =
        client.request("PUT", "/v1/conversations/${seg(id)}/accounts/${seg(account)}/role", encodeBody(RoleBody(role)), Auth.Session, MembersChangeWire.serializer())
            .toChange()

    override suspend fun conversation(id: String): ConversationInfo =
        client.request("GET", "/v1/conversations/${seg(id)}", null, Auth.Session, ConversationWire.serializer()).toInfo()

    override suspend fun postCommit(
        id: String,
        epoch: Long,
        commit: ByteArray,
        welcome: ByteArray?,
        addDevices: List<String>,
        removeDevices: List<String>,
    ): Long =
        client.request(
            "POST", "/v1/conversations/${seg(id)}/commits",
            encodeBody(CommitBody(epoch, commit, welcome, addDevices, removeDevices)), Auth.Session, EpochWire.serializer(),
        ).epoch

    override suspend fun postMessage(id: String, epoch: Long, ciphertext: ByteArray, escrow: ByteArray?, clientMsgId: String): Long =
        postMessage(id, epoch, ciphertext, escrow, clientMsgId, null)

    /** [expiresAtMs] goes out as an RFC 3339 `expiresAt`; the server needs it after now and at most 366 days ahead. */
    override suspend fun postMessage(
        id: String, epoch: Long, ciphertext: ByteArray, escrow: ByteArray?, clientMsgId: String, expiresAtMs: Long?,
    ): Long =
        client.request(
            "POST", "/v1/conversations/${seg(id)}/messages",
            encodeBody(MessageBody(epoch, ciphertext, escrow, clientMsgId, expiresAtMs?.let { java.time.Instant.ofEpochMilli(it).toString() })),
            Auth.Session, SeqWire.serializer(),
        ).seq

    override suspend fun inbox(after: Long?, limit: Int): InboxPage {
        val query = buildString {
            if (after != null) append("after=$after&")
            append("limit=$limit")
        }
        val w = client.request("GET", "/v1/inbox?$query", null, Auth.Session, InboxWire.serializer())
        return InboxPage(
            w.items.map {
                InboxItem(it.cursor, it.kind, it.conversationID, it.seq, it.epoch, it.senderAccountID, it.senderDeviceID, it.payload, it.sentAt, it.membersVersion)
            },
            w.more,
        )
    }

    override suspend fun ack(upTo: Long) {
        client.requestNoContent("POST", "/v1/inbox/ack", encodeBody(AckBody(upTo)), Auth.Session)
    }

    override suspend fun devices(): List<DeviceRecord> =
        client.request("GET", "/v1/devices", null, Auth.Session, DevicesWire.serializer()).devices.map { it.toRecord() }

    override suspend fun uploadAttachment(bytes: InputStream, length: Long): AttachmentRef = attachments.upload(bytes, length)

    override suspend fun downloadAttachment(id: String, fromByte: Long): InputStream = attachments.download(id, fromByte)

    /**
     * The member list is hostile input: at most 100 distinct accounts, a version of at least 0, a role this version does not
     * know read as `member` (the least it can be). Anything else is refused as `decoding`, like any reply that does not parse.
     */
    private fun ConversationWire.toInfo(): ConversationInfo {
        if (kind != "direct" && kind != "group") throw badResponse()
        if (membersVersion != null && membersVersion < 0) throw badResponse()
        if (members.size > MAX_GROUP_MEMBERS || members.map { it.account }.toSet().size != members.size || members.any { it.account.isEmpty() }) {
            throw badResponse()
        }
        val roles = members.map { GroupMemberInfo(it.account, if (it.role in KNOWN_ROLES) it.role else GroupRoleWire.MEMBER) }
        return ConversationInfo(conversationID, mode, epoch, kind, roles, membersVersion)
    }

    private fun MembersChangeWire.toChange(): MembersChange {
        if (membersVersion < 0) throw badResponse()
        return MembersChange(membersVersion, deleted)
    }

    private fun badResponse() = ServerException(0, "decoding", null, null)

    private companion object {
        const val MAX_GROUP_MEMBERS = 100
        val KNOWN_ROLES = setOf(GroupRoleWire.OWNER, GroupRoleWire.ADMIN, GroupRoleWire.MEMBER)
    }
}
