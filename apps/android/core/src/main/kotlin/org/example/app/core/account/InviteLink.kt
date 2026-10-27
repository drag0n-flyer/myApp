package org.example.app.core.account

import java.net.URI

/**
 * An invite: the code (`XXXXX-XXXXX`) and, when it came from an app link, the server host
 * (`https://<host>[:port]/invite/<code>`). The host keeps its port, if the link has one.
 */
data class InviteLink(val host: String?, val code: String) {
    companion object {
        /** No I, O, 0 or 1. */
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** Accepts a typed code or an app link; returns null for anything else. */
        fun parse(text: String): InviteLink? {
            val trimmed = text.trim()
            if (trimmed.contains("://")) return parseLink(trimmed)
            return validCode(trimmed.uppercase())?.let { InviteLink(null, it) }
        }

        private fun parseLink(text: String): InviteLink? {
            val uri = try { URI(text) } catch (_: Exception) { return null }
            val name = uri.host
            if (uri.scheme != "https" || name.isNullOrEmpty()) return null
            val host = if (uri.port != -1) "$name:${uri.port}" else name
            val parts = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
            if (parts.size != 2 || parts[0] != "invite") return null
            return validCode(parts[1].uppercase())?.let { InviteLink(host, it) }
        }

        private fun validCode(code: String): String? {
            if (code.length != 11 || code[5] != '-') return null
            val ok = code.withIndex().all { (i, c) -> i == 5 || c in ALPHABET }
            return if (ok) code else null
        }
    }
}
