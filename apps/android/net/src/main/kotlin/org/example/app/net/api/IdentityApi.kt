package org.example.app.net.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.example.app.contracts.DeviceRecord
import org.example.app.contracts.ServerException
import org.example.app.net.Auth
import org.example.app.net.Base64Url
import org.example.app.net.ServerClient
import org.example.app.net.ServerJson
import org.example.app.net.models.*

/** Server design section 7.1. WebAuthn options and credentials are JSON for the system credential manager. */
class IdentityApi(private val client: ServerClient) {
    suspend fun usernameAvailable(name: String): Boolean =
        flag("GET", "/v1/usernames/${seg(name)}", null, Auth.None, "available")

    suspend fun checkInvite(code: String): Boolean =
        flag("POST", "/v1/invites/check", buildJsonObject { put("code", code) }, Auth.None, "valid")

    /** Returns the sign-up ID. */
    suspend fun signupStart(inviteCode: String?, username: String, email: String): String {
        val body = buildJsonObject {
            if (inviteCode != null) put("inviteCode", inviteCode)
            put("username", username)
            put("email", email)
        }
        return text("POST", "/v1/signup/start", body, Auth.None, "signupID")
    }

    suspend fun signupVerify(signupID: String, code: String) {
        client.requestNoContent("POST", "/v1/signup/verify", buildJsonObject { put("signupID", signupID); put("code", code) }, Auth.None)
    }

    suspend fun signupPasskeyOptions(signupID: String): JsonObject =
        client.request("POST", "/v1/signup/passkey/options", buildJsonObject { put("signupID", signupID) }, Auth.None, JsonObject.serializer())

    suspend fun signupFinish(signupID: String, credential: JsonObject?, deviceName: String, platform: String): NewSession {
        val body = buildJsonObject {
            put("signupID", signupID)
            if (credential != null) put("credential", credential)
            put("deviceName", deviceName)
            put("platform", platform)
        }
        return client.request("POST", "/v1/signup/finish", body, Auth.None, NewSession.serializer())
    }

    suspend fun signinPasskeyOptions(): SigninOptions {
        val o = client.request("POST", "/v1/signin/passkey/options", emptyObject, Auth.None, JsonObject.serializer())
        val id = (o["challengeID"] as? JsonPrimitive)?.contentOrNull
        val options = o["options"] as? JsonObject
        if (id == null || options == null) throw ServerException(0, "decoding", null, null)
        return SigninOptions(id, options)
    }

    suspend fun signinPasskeyFinish(challengeID: String, credential: JsonObject, deviceName: String, platform: String): NewSession {
        val body = buildJsonObject {
            put("challengeID", challengeID)
            put("credential", credential)
            put("deviceName", deviceName)
            put("platform", platform)
        }
        return client.request("POST", "/v1/signin/passkey/finish", body, Auth.None, NewSession.serializer())
    }

    /** The server answers 202 whether or not the email exists. */
    suspend fun signinEmailStart(email: String) {
        client.requestNoContent("POST", "/v1/signin/email/start", buildJsonObject { put("email", email) }, Auth.None)
    }

    suspend fun signinEmailFinish(email: String, code: String, deviceName: String, platform: String): NewSession {
        val body = buildJsonObject {
            put("email", email)
            put("code", code)
            put("deviceName", deviceName)
            put("platform", platform)
        }
        return client.request("POST", "/v1/signin/email/finish", body, Auth.None, NewSession.serializer())
    }

    suspend fun addPasskeyOptions(): JsonObject =
        client.request("POST", "/v1/me/passkeys/options", emptyObject, Auth.Session, JsonObject.serializer())

    suspend fun addPasskey(credential: JsonObject) {
        client.requestNoContent("POST", "/v1/me/passkeys", buildJsonObject { put("credential", credential) }, Auth.Session)
    }

    suspend fun me(): Me = client.request("GET", "/v1/me", null, Auth.Session, Me.serializer())

    suspend fun deleteMe() {
        client.requestNoContent("DELETE", "/v1/me", null, Auth.Session)
    }

    suspend fun devices(): List<DeviceRecord> =
        client.request("GET", "/v1/devices", null, Auth.Session, DevicesWire.serializer()).devices.map { it.toRecord() }

    suspend fun revokeDevice(id: String) {
        client.requestNoContent("DELETE", "/v1/devices/${seg(id)}", null, Auth.Session)
    }

    suspend fun linkStart(deviceName: String, platform: String): LinkTicket =
        client.request(
            "POST", "/v1/link", buildJsonObject { put("deviceName", deviceName); put("platform", platform) },
            Auth.None, LinkTicket.serializer(),
        )

    suspend fun linkInfo(linkID: String, secret: String): LinkInfo =
        client.request("POST", "/v1/link/${seg(linkID)}/info", buildJsonObject { put("secret", secret) }, Auth.Session, LinkInfo.serializer())

    suspend fun linkApprove(linkID: String, secret: String, sealed: ByteArray?) {
        val body = buildJsonObject {
            put("secret", secret)
            if (sealed != null) put("sealed", Base64Url.encode(sealed))
        }
        client.requestNoContent("POST", "/v1/link/${seg(linkID)}/approve", body, Auth.Session)
    }

    suspend fun linkClaim(linkID: String, claimToken: String): LinkClaim {
        val json = ServerJson.json.encodeToString(JsonElement.serializer(), buildJsonObject { put("claimToken", claimToken) })
        val res = client.requestRaw(
            "POST", "/v1/link/${seg(linkID)}/claim", json.toRequestBody("application/json".toMediaType()), emptyMap(), Auth.None,
        )
        if (res.status == 202) return LinkClaim.Pending
        val w = decodeBody(res.body, LinkClaimWire.serializer())
        return LinkClaim.Approved(NewSession(w.accountID, w.deviceID, w.sessionToken), w.sealed)
    }

    private suspend fun flag(method: String, path: String, body: JsonObject?, auth: Auth, key: String): Boolean =
        client.request(method, path, body, auth, JsonObject.serializer())[key]?.jsonPrimitive?.booleanOrNull
            ?: throw ServerException(0, "decoding", null, null)

    private suspend fun text(method: String, path: String, body: JsonObject?, auth: Auth, key: String): String =
        (client.request(method, path, body, auth, JsonObject.serializer())[key] as? JsonPrimitive)?.contentOrNull
            ?: throw ServerException(0, "decoding", null, null)
}

private const val YEAR_ONE_SECONDS = -62135596800L

internal fun DeviceWire.toRecord() = DeviceRecord(
    id, name, platform, createdAt,
    lastSeenAt?.takeIf { it.epochSecond > YEAR_ONE_SECONDS }, current,
)
