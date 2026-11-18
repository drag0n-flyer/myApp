package org.example.app.core.chats

/** Finds web links in message text: `http://`, `https://` and `www.` only. */
object LinkFinder {
    data class Link(val range: IntRange, val url: String)

    private val pattern = Regex("""(?<![\w/])(?:https?://|www\.)[^\s<>]+""", RegexOption.IGNORE_CASE)
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
        val host = body.substringAfter("://", body.removePrefix("www.").removePrefix("WWW."))
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
