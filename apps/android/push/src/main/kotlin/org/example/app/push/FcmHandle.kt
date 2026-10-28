package org.example.app.push

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The plaintext of a sealed FCM handle (docs/protocol/push.md). */
object FcmHandle {
    fun plaintext(token: String, appId: String): ByteArray = buildJsonObject {
        put("t", token)
        put("k", "fcm")
        put("e", "production")
        put("b", appId)
    }.toString().toByteArray(Charsets.UTF_8)
}
