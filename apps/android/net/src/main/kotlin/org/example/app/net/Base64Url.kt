package org.example.app.net

import java.util.Base64

/** Binary fields on the wire are base64url without padding. Decoding also accepts padding. */
object Base64Url {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Returns null for anything that is not valid base64url. */
    fun decodeOrNull(text: String): ByteArray? {
        val s = text.trimEnd('=')
        if (s.length % 4 == 1) return null
        if (s.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') }) return null
        return try {
            Base64.getUrlDecoder().decode(s)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun decode(text: String): ByteArray = decodeOrNull(text) ?: throw IllegalArgumentException("invalid base64url")
}
