package org.example.app.push

import java.net.URI
import java.util.Locale
import org.example.app.contracts.Hpke
import org.example.app.contracts.HpkeInfo
import org.example.app.contracts.ServerException

/** What the client uploads to the server. Implemented by `:net`, which does the base64url encoding. */
interface PushApi {
    /** `push.gateway` from `/v1/server`, or null when the server names none. */
    suspend fun serverGatewayUrl(): String?

    /** `push.vapidKey` from `/v1/server`, or null when the server has no UnifiedPush support. */
    suspend fun serverVapidKey(): String?

    /** `PUT /v1/push/tokens/fcm {sealed}` */
    suspend fun putFcm(sealed: ByteArray)

    /** `PUT /v1/push/tokens/unifiedpush {endpoint, p256dh, auth}` */
    suspend fun putUnifiedPush(endpoint: String, p256dh: ByteArray, auth: ByteArray)

    /** `DELETE /v1/push/tokens/{kind}`; [kind] is `fcm` or `unifiedpush`. Deleting a missing one succeeds. */
    suspend fun delete(kind: String)
}

/** The transport's own credential. The UnifiedPush endpoint is a capability URL: never log it. */
sealed interface PushRegistration {
    class Fcm(val token: String) : PushRegistration

    class UnifiedPush(val endpoint: String, val p256dh: ByteArray, val auth: ByteArray) : PushRegistration
}

class PushRegistrar(
    private val api: PushApi,
    private val hpke: Hpke,
    private val gatewayKey: ByteArray,
    private val gatewayUrl: String,
    private val appId: String,
) {
    /**
     * Uploads [registration] for [transport] and removes the other kind. Returns false when nothing was
     * registered: no transport, a mismatched registration, no gateway key, or a server that names another gateway.
     */
    suspend fun register(transport: PushTransport, registration: PushRegistration?): Boolean = when {
        transport == PushTransport.Fcm && registration is PushRegistration.Fcm -> registerFcm(registration.token)
        transport == PushTransport.UnifiedPush && registration is PushRegistration.UnifiedPush -> {
            api.putUnifiedPush(registration.endpoint, registration.p256dh, registration.auth)
            deleteQuietly("fcm")
            true
        }
        else -> false
    }

    /** Removes both kinds, for example on sign-out. */
    suspend fun unregister() {
        deleteQuietly("fcm")
        deleteQuietly("unifiedpush")
    }

    private suspend fun registerFcm(token: String): Boolean {
        // A server that names another gateway could collect tokens through it: send it none.
        if (gatewayKey.isEmpty() || gatewayUrl.isEmpty()) return false
        val named = api.serverGatewayUrl() ?: return false
        if (normalise(named) != normalise(gatewayUrl)) return false
        val sealed = hpke.seal(gatewayKey, HpkeInfo.PUSH_HANDLE, FcmHandle.plaintext(token, appId))
        api.putFcm(sealed)
        deleteQuietly("unifiedpush")
        return true
    }

    private suspend fun deleteQuietly(kind: String) {
        try {
            api.delete(kind)
        } catch (e: ServerException) {
            if (e.status != 404) throw e
        }
    }

    private fun normalise(url: String): String {
        val u = try { URI(url.trim()) } catch (_: Exception) { return url.trim() }
        val host = u.host?.lowercase(Locale.ROOT) ?: return url.trim()
        val port = if (u.port == -1 || (u.scheme.equals("https", true) && u.port == 443)) "" else ":${u.port}"
        return "${u.scheme.lowercase(Locale.ROOT)}://$host$port${u.rawPath.trimEnd('/')}"
    }
}
