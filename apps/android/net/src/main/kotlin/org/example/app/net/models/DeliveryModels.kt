package org.example.app.net.models

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.example.app.net.Base64UrlSerializer
import org.example.app.net.InstantSerializer

// Wire shapes of server design section 7.2 and 7.5. The public types come from `:contracts`.

@Serializable
internal class CountWire(@SerialName("count") val count: Int)

@Serializable
internal class UploadKeyPackagesBody(
    @SerialName("keyPackages") val keyPackages: List<@Serializable(with = Base64UrlSerializer::class) ByteArray>,
)

@Serializable
internal class ClaimBody(
    @SerialName("accountID") val accountID: String,
    @SerialName("deviceIDs") val deviceIDs: List<String>? = null,
)

@Serializable
internal class ClaimedKeyPackage(
    @SerialName("deviceID") val deviceID: String,
    @SerialName("keyPackage") @Serializable(with = Base64UrlSerializer::class) val keyPackage: ByteArray,
)

@Serializable
internal class ClaimResponse(@SerialName("keyPackages") val keyPackages: List<ClaimedKeyPackage>)

@Serializable
internal class CreateConversationBody(@SerialName("members") val members: List<String>)

@Serializable
internal class ConversationWire(
    @SerialName("conversationID") val conversationID: String,
    @SerialName("mode") val mode: String,
    @SerialName("epoch") val epoch: Long,
)

@Serializable
internal class CommitBody(
    @SerialName("epoch") val epoch: Long,
    @SerialName("commit") @Serializable(with = Base64UrlSerializer::class) val commit: ByteArray,
    @SerialName("welcome") @Serializable(with = Base64UrlSerializer::class) val welcome: ByteArray? = null,
    @SerialName("addDevices") val addDevices: List<String>,
    @SerialName("removeDevices") val removeDevices: List<String>,
)

@Serializable
internal class EpochWire(@SerialName("epoch") val epoch: Long)

@Serializable
internal class MessageBody(
    @SerialName("epoch") val epoch: Long,
    @SerialName("ciphertext") @Serializable(with = Base64UrlSerializer::class) val ciphertext: ByteArray,
    @SerialName("escrow") @Serializable(with = Base64UrlSerializer::class) val escrow: ByteArray? = null,
    @SerialName("clientMsgID") val clientMsgID: String,
)

@Serializable
internal class SeqWire(@SerialName("seq") val seq: Long)

@Serializable
internal class InboxItemWire(
    @SerialName("cursor") val cursor: Long,
    @SerialName("kind") val kind: String,
    @SerialName("conversationID") val conversationID: String,
    @SerialName("seq") val seq: Long? = null,
    @SerialName("epoch") val epoch: Long,
    @SerialName("senderAccountID") val senderAccountID: String,
    @SerialName("senderDeviceID") val senderDeviceID: String,
    @SerialName("payload") @Serializable(with = Base64UrlSerializer::class) val payload: ByteArray,
    @SerialName("sentAt") @Serializable(with = InstantSerializer::class) val sentAt: Instant,
)

@Serializable
internal class InboxWire(
    @SerialName("items") val items: List<InboxItemWire>,
    @SerialName("more") val more: Boolean = false,
)

@Serializable
internal class AckBody(@SerialName("upTo") val upTo: Long)

@Serializable
internal class DeviceWire(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("platform") val platform: String,
    @SerialName("createdAt") @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    // Go writes an unset time as year 1; it means "never".
    @SerialName("lastSeenAt") @Serializable(with = InstantSerializer::class) val lastSeenAt: Instant? = null,
    @SerialName("current") val current: Boolean,
)

@Serializable
internal class DevicesWire(@SerialName("devices") val devices: List<DeviceWire>)

@Serializable
class UploadedAttachment(
    @SerialName("id") val id: String,
    @SerialName("expiresAt") @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
)
