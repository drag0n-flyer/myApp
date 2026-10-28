package org.example.app.net.account

import java.net.URI
import org.example.app.core.account.AccountError
import org.example.app.net.Base64Url

/**
 * The device-linking QR text: `https://<host>/link/<linkID>#<secret>.<X25519 public key, base64url>`
 * (docs/protocol/device-linking.md). The fragment never reaches a server in an HTTP request.
 */
object LinkQr {
    const val KEY_BYTES = 32

    class Parsed(val linkId: String, val secret: String, val publicKey: ByteArray)

    /** [host] is `host` or `host:port`, as stored in the session. */
    fun build(host: String, linkId: String, secret: String, publicKey: ByteArray): String {
        require(publicKey.size == KEY_BYTES)
        return "https://$host/link/$linkId#$secret.${Base64Url.encode(publicKey)}"
    }

    /**
     * The host is checked first: a code for any other server is [AccountError.WrongServer] whatever else is
     * wrong with it, so nothing about it is ever sent anywhere. Any other defect is [AccountError.LinkExpired]
     * ("start again on the new device").
     */
    fun parse(text: String, ownHost: String): Parsed {
        val uri = try {
            URI(text.trim())
        } catch (_: Exception) {
            throw AccountError.LinkExpired
        }
        val authority = uri.rawAuthority ?: throw AccountError.LinkExpired
        // userinfo (`own@evil`) or any other authority than exactly our host[:port] is another server.
        if (!authority.equals(ownHost, ignoreCase = true)) throw AccountError.WrongServer
        if (!"https".equals(uri.scheme, ignoreCase = true)) throw AccountError.LinkExpired
        val parts = (uri.rawPath ?: "").split('/')
        if (parts.size != 3 || parts[0].isNotEmpty() || parts[1] != "link" || parts[2].isEmpty()) throw AccountError.LinkExpired
        val fragment = uri.rawFragment ?: throw AccountError.LinkExpired
        val bits = fragment.split('.')
        if (bits.size != 2 || bits[0].isEmpty()) throw AccountError.LinkExpired
        val key = Base64Url.decodeOrNull(bits[1])?.takeIf { it.size == KEY_BYTES } ?: throw AccountError.LinkExpired
        return Parsed(parts[2], bits[0], key)
    }
}
