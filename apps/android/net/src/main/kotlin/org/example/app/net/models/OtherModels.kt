package org.example.app.net.models

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.example.app.net.Base64UrlSerializer
import org.example.app.net.InstantSerializer

@Serializable
class UserSummary(
    @SerialName("accountID") val accountID: String,
    @SerialName("username") val username: String,
    @SerialName("displayName") val displayName: String,
)

@Serializable
class ReportedMessage(
    @SerialName("sentAt") @Serializable(with = InstantSerializer::class) val sentAt: Instant,
    @SerialName("text") val text: String,
)

@Serializable
internal class ReportBody(
    @SerialName("accountID") val accountID: String,
    @SerialName("conversationID") val conversationID: String? = null,
    @SerialName("reason") val reason: String,
    @SerialName("messages") val messages: List<ReportedMessage>,
    @SerialName("note") val note: String? = null,
)

@Serializable
internal class SettingsBody(
    @SerialName("displayName") val displayName: String? = null,
    @SerialName("findByEmail") val findByEmail: Boolean? = null,
)

@Serializable
internal class BlocksWire(@SerialName("accountIDs") val accountIDs: List<String>)

@Serializable
class IceServer(
    @SerialName("urls") val urls: List<String>,
    @SerialName("username") val username: String,
    @SerialName("credential") val credential: String,
)

@Serializable
class TurnCredentials(
    @SerialName("iceServers") val iceServers: List<IceServer>,
    @SerialName("ttl") val ttl: Int,
)

@Serializable
class ServerPolicy(
    @SerialName("defaultMode") val defaultMode: String,
    @SerialName("maxMode") val maxMode: String,
    @SerialName("defaultIPHiding") val defaultIPHiding: String,
)

@Serializable
class ServerEncryption(
    @SerialName("newConversations") val newConversations: String,
    @SerialName("escrowKey") @Serializable(with = Base64UrlSerializer::class) val escrowKey: ByteArray? = null,
)

/** [vapidKey] is only present on servers that support Web Push for Android (UnifiedPush); older servers omit it. */
@Serializable
class ServerPush(
    @SerialName("gateway") val gateway: String? = null,
    @SerialName("vapidKey") val vapidKey: String? = null,
)

@Serializable
class ServerLimits(@SerialName("attachmentBytes") val attachmentBytes: Long)

@Serializable
class ServerInfo(
    @SerialName("protocol") val protocol: Int,
    @SerialName("host") val host: String,
    @SerialName("requiresInvite") val requiresInvite: Boolean,
    @SerialName("policy") val policy: ServerPolicy,
    @SerialName("encryption") val encryption: ServerEncryption,
    @SerialName("push") val push: ServerPush,
    @SerialName("features") val features: List<String> = emptyList(),
    @SerialName("federation") val federation: Boolean = false,
    @SerialName("limits") val limits: ServerLimits,
)
