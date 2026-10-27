package org.example.app.net.api

import kotlinx.serialization.json.JsonObject
import org.example.app.net.Auth
import org.example.app.net.ServerClient

/** Server design section 7.4. [kind] is `alert`, `fcm` or `unifiedpush`; the body shape depends on the kind. */
class PushTokenApi(private val client: ServerClient) {
    suspend fun putPushToken(kind: String, body: JsonObject) {
        client.requestNoContent("PUT", "/v1/push/tokens/${seg(kind)}", body, Auth.Session)
    }

    suspend fun deletePushToken(kind: String) {
        client.requestNoContent("DELETE", "/v1/push/tokens/${seg(kind)}", null, Auth.Session)
    }
}
