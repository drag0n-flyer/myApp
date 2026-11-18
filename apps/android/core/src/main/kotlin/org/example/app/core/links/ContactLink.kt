package org.example.app.core.links

import java.net.URI
import java.util.Locale
import org.example.app.core.account.UsernameCheck

/**
 * What a link to add a person is: `https://<host>/u/<username>` (a person's QR code) or `https://<host>/c/<token>` (a
 * one-time link). A [Invite] token is a secret: never log it or copy it.
 */
sealed class ContactLink {
    data class User(val username: String) : ContactLink()

    /** The token is a secret; [toString] hides it. */
    data class Invite(val token: String) : ContactLink() {
        override fun toString() = "Invite(token=<hidden>)"
    }

    /** A well-formed link to a server other than ours. */
    data object OtherServer : ContactLink()
    data object NotALink : ContactLink()

    companion object {
        private const val TOKEN_LENGTH = 22

        /**
         * https only; the host (and port, when [serverHost] has one) equal to [serverHost] ignoring case; the path exactly
         * `/u/<username>` or `/c/<22 URL-safe base64 characters>` with nothing after it (not even a slash); a query or
         * fragment is ignored. A link to another host is [OtherServer], whatever its path.
         */
        fun parse(url: String, serverHost: String): ContactLink {
            val uri = try { URI(url.trim()) } catch (_: Exception) { return NotALink }
            val name = uri.host
            if (!"https".equals(uri.scheme, ignoreCase = true) || name.isNullOrEmpty() || uri.userInfo != null) return NotALink
            val host = if (uri.port != -1 && uri.port != 443) "$name:${uri.port}" else name
            val path = uri.rawPath ?: return NotALink
            val parts = path.split('/')
            // "" before the first slash, the kind, the value: nothing else.
            if (parts.size != 3 || parts[0].isNotEmpty()) return NotALink
            val found = when (parts[1]) {
                "u" -> parts[2].takeIf { it.isNotEmpty() && UsernameCheck.isValid(it) }?.let(::User)
                "c" -> parts[2].takeIf { it.length == TOKEN_LENGTH && it.all(::isTokenChar) }?.let(::Invite)
                else -> null
            } ?: return NotALink
            return if (host.lowercase(Locale.ROOT) == serverHost.trim().lowercase(Locale.ROOT).removeSuffix(":443")) found else OtherServer
        }

        private fun isTokenChar(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'
    }
}
