package org.example.app.net.models

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.example.app.net.Base64UrlSerializer
import org.example.app.net.InstantSerializer

@Serializable
data class NewSession(
    @SerialName("accountID") val accountID: String,
    @SerialName("deviceID") val deviceID: String,
    @SerialName("sessionToken") val sessionToken: String,
) {
    override fun toString() = "NewSession(accountID=$accountID, deviceID=$deviceID)"
}

@Serializable
data class Me(
    @SerialName("accountID") val accountID: String,
    @SerialName("username") val username: String,
    @SerialName("email") val email: String,
    @SerialName("displayName") val displayName: String,
    @SerialName("findByEmail") val findByEmail: Boolean,
)

@Serializable
data class LinkTicket(
    @SerialName("linkID") val linkID: String,
    @SerialName("secret") val secret: String,
    @SerialName("claimToken") val claimToken: String,
    @SerialName("expiresAt") @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
) {
    /** The secret and claim token are never printed. */
    override fun toString() = "LinkTicket(linkID=$linkID, expiresAt=$expiresAt)"
}

@Serializable
data class LinkInfo(
    @SerialName("deviceName") val deviceName: String,
    @SerialName("platform") val platform: String,
    @SerialName("createdAt") @Serializable(with = InstantSerializer::class) val createdAt: Instant,
)

/** 202 is [Pending]; 200 is [Approved], returned exactly once. */
sealed interface LinkClaim {
    data object Pending : LinkClaim
    class Approved(val session: NewSession, val sealed: ByteArray?) : LinkClaim
}

/** The WebAuthn request options are passed on as JSON, for the system credential manager. */
class SigninOptions(val challengeID: String, val options: JsonObject)

@Serializable
internal class LinkClaimWire(
    @SerialName("accountID") val accountID: String,
    @SerialName("deviceID") val deviceID: String,
    @SerialName("sessionToken") val sessionToken: String,
    @SerialName("sealed") @Serializable(with = Base64UrlSerializer::class) val sealed: ByteArray? = null,
)
