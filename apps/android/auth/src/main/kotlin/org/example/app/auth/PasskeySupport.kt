package org.example.app.auth

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json

/**
 * Whether a server can use passkeys with this app: its `https://<host>/.well-known/assetlinks.json`
 * must list this app's package and signing-certificate SHA-256 for `delegate_permission/common.get_login_creds`.
 * The caller fetches the file with one GET (no redirects to another host) and passes the body, or null
 * when the file is missing or the request failed. [host] is only checked for being non-blank: the caller binds
 * the body to `https://<host>/.well-known/assetlinks.json`. Pure: no network, no logging.
 */
object PasskeySupport {
    const val RELATION = "delegate_permission/common.get_login_creds"

    fun supported(host: String, assetLinksJson: String?, packageName: String, certSha256: ByteArray): Boolean {
        if (host.isBlank() || assetLinksJson.isNullOrBlank() || certSha256.size != 32) return false
        val statements = try {
            Json.parseToJsonElement(assetLinksJson) as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return false
        val ours = certSha256.joinToString("") { "%02X".format(it) }
        return statements.any { statement ->
            val obj = statement as? JsonObject ?: return@any false
            val relations = (obj["relation"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            if (RELATION !in relations) return@any false
            val target = obj["target"] as? JsonObject ?: return@any false
            if ((target["namespace"] as? JsonPrimitive)?.contentOrNull != "android_app") return@any false
            if ((target["package_name"] as? JsonPrimitive)?.contentOrNull != packageName) return@any false
            (target["sha256_cert_fingerprints"] as? JsonArray).orEmpty().any {
                val listed = (it as? JsonPrimitive)?.contentOrNull ?: return@any false
                listed.filter { c -> c != ':' }.uppercase() == ours
            }
        }
    }
}
