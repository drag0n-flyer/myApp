package org.example.app.core.account

/**
 * A server the person typed or an invite named: `host` or `host:port`, nothing else. The one place
 * that decides what is acceptable, for the address field, "Change server" and invite hosts.
 *
 * Rules: at most [MAX_LENGTH] characters; no userinfo, path, query, fragment or whitespace; a
 * hostname, IPv4 address or bracketed IPv6 address; port 1-65535. `https` is the default and the
 * only scheme accepted, except plain `http` for loopback (`localhost`, `127.0.0.1`, `::1`).
 */
data class ServerAddress(val host: String, val port: Int?, val secure: Boolean) {
    /** `host`, `host:port` or `[v6]:port`: what `AccountService.connect` takes. */
    val hostPort: String
        get() {
            val h = if (':' in host) "[$host]" else host
            return if (port != null) "$h:$port" else h
        }

    companion object {
        const val MAX_LENGTH = 253
        private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1")

        /** The normalised address, or null when [text] breaks a rule above. */
        fun parse(text: String): ServerAddress? {
            val input = text.trim()
            if (input.isEmpty() || input.length > MAX_LENGTH) return null
            if (input.any { it.isWhitespace() || it.isISOControl() }) return null
            var rest = input
            var secure = true
            val schemeEnd = input.indexOf("://")
            if (schemeEnd >= 0) {
                when (input.substring(0, schemeEnd).lowercase()) {
                    "https" -> {}
                    "http" -> secure = false
                    else -> return null
                }
                rest = input.substring(schemeEnd + 3)
            }
            rest = rest.lowercase()
            if (rest.any { it in "/?#@\\" }) return null

            val host: String
            val portText: String?
            when {
                rest.startsWith("[") -> {
                    val end = rest.indexOf(']')
                    if (end < 0) return null
                    host = rest.substring(1, end)
                    val tail = rest.substring(end + 1)
                    portText = when {
                        tail.isEmpty() -> null
                        tail.startsWith(":") -> tail.substring(1)
                        else -> return null
                    }
                    if (!validIpv6(host)) return null
                }
                rest == "::1" -> { host = "::1"; portText = null }
                else -> {
                    val colons = rest.count { it == ':' }
                    if (colons > 1) return null
                    val at = rest.indexOf(':')
                    host = if (at >= 0) rest.substring(0, at) else rest
                    portText = if (at >= 0) rest.substring(at + 1) else null
                    if (!validHostname(host)) return null
                }
            }
            val port = if (portText == null) null else portText.toIntOrNull()?.takeIf { it in 1..65535 && portText.all(Char::isDigit) } ?: return null
            if (!secure && host !in LOOPBACK) return null
            return ServerAddress(host, port, secure)
        }

        private fun validIpv6(h: String) = h.count { it == ':' } >= 2 && h.all { it in '0'..'9' || it in 'a'..'f' || it == ':' }

        private fun validHostname(h: String): Boolean {
            if (h.isEmpty()) return false
            return h.split('.').all { label ->
                label.length in 1..63 && !label.startsWith('-') && !label.endsWith('-') &&
                    label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            }
        }
    }
}
