package org.example.app.net.api

import java.io.InputStream
import kotlinx.serialization.json.JsonObject
import org.example.app.contracts.AttachmentRef
import org.example.app.contracts.ConversationInfo
import org.example.app.contracts.DeliveryApi
import org.example.app.contracts.DeviceRecord
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
        client.request(
            "POST", "/v1/conversations/${seg(id)}/messages",
            encodeBody(MessageBody(epoch, ciphertext, escrow, clientMsgId)), Auth.Session, SeqWire.serializer(),
        ).seq

    override suspend fun inbox(after: Long?, limit: Int): InboxPage {
        val query = buildString {
            if (after != null) append("after=$after&")
            append("limit=$limit")
        }
        val w = client.request("GET", "/v1/inbox?$query", null, Auth.Session, InboxWire.serializer())
        return InboxPage(
            w.items.map {
                InboxItem(it.cursor, it.kind, it.conversationID, it.seq, it.epoch, it.senderAccountID, it.senderDeviceID, it.payload, it.sentAt)
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

    private fun ConversationWire.toInfo() = ConversationInfo(conversationID, mode, epoch)
}
