package org.example.app.core.chats

/**
 * Finds links in message text: `http://`, `https://`, `www.` and `mailto:` only. A link containing bidirectional control
 * characters is skipped: it could display as one address and open another.
 */
object LinkFinder {
    data class Link(val range: IntRange, val url: String)

    private val pattern = Regex("""(?<![\w/])(?:https?://|www\.|mailto:)[^\s<>]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?'\""

    fun find(text: String): List<Link> = pattern.findAll(text).mapNotNull { match ->
        val start = match.range.first
        var end = match.range.last
        while (end >= start) {
            val c = text[end]
            val unbalancedClose = (c == ')' && count(text, start, end, '(') < count(text, start, end, ')')) ||
                (c == ']' && count(text, start, end, '[') < count(text, start, end, ']'))
            if (c in TRAILING || unbalancedClose) end-- else break
        }
        val body = text.substring(start, end + 1)
        if (body.any { it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }) return@mapNotNull null
        val host = body.substringAfter("://", body.removePrefix("www.").removePrefix("WWW.")).removePrefix("mailto:").removePrefix("MAILTO:")
        // A bare scheme or "www." with nothing after it is not a link.
        if (host.isEmpty()) return@mapNotNull null
        Link(start..end, if (body.startsWith("www.", ignoreCase = true)) "https://$body" else body)
    }.toList()

    private fun count(text: String, from: Int, to: Int, c: Char): Int {
        var n = 0
        for (i in from..to) if (text[i] == c) n++
        return n
    }
}
