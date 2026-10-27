package org.example.app.net.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.app.net.Auth
import org.example.app.net.ServerClient
import org.example.app.net.models.*

/** Server design section 7.5, social part. */
class SocialApi(private val client: ServerClient) {
    suspend fun lookUpUser(username: String): UserSummary =
        client.request("GET", "/v1/users/${seg(username)}", null, Auth.Session, UserSummary.serializer())

    suspend fun discover(email: String): UserSummary =
        client.request("POST", "/v1/discover/email", buildJsonObject { put("email", email) }, Auth.Session, UserSummary.serializer())

    suspend fun updateSettings(displayName: String?, findByEmail: Boolean?) {
        client.requestNoContent("PATCH", "/v1/me/settings", encodeBody(SettingsBody(displayName, findByEmail)), Auth.Session)
    }

    suspend fun block(accountID: String) {
        client.requestNoContent("PUT", "/v1/blocks/${seg(accountID)}", null, Auth.Session)
    }

    suspend fun unblock(accountID: String) {
        client.requestNoContent("DELETE", "/v1/blocks/${seg(accountID)}", null, Auth.Session)
    }

    suspend fun blocks(): List<String> =
        client.request("GET", "/v1/blocks", null, Auth.Session, BlocksWire.serializer()).accountIDs

    /** [reason] is `spam`, `abuse` or `other`; at most 50 messages. */
    suspend fun report(accountID: String, conversationID: String?, reason: String, messages: List<ReportedMessage>, note: String?) {
        client.requestNoContent("POST", "/v1/reports", encodeBody(ReportBody(accountID, conversationID, reason, messages, note)), Auth.Session)
    }
}
