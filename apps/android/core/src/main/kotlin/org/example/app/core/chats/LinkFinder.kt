package org.example.app.core.chats

/**
 * Finds links in message text: `http://`, `https://`, `www.` and `mailto:` only. A message that contains any Unicode
 * format character for text direction gets no links at all: such characters can make a link display as one address and
 * open another.
 */
object LinkFinder {
    data class Link(val range: IntRange, val url: String)

    private val pattern = Regex("""(?<![\w/])(?:https?://|www\.|mailto:)[^\s<>]+""", RegexOption.IGNORE_CASE)
    private val prefix = Regex("""^(?:https?://|www\.|mailto:)""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?'\""

    private fun isDirectionFormat(c: Char) =
        c in '‪'..'‮' || c in '⁦'..'⁩' || c == '‎' || c == '‏' || c == '؜'

    fun find(text: String): List<Link> {
        if (text.any(::isDirectionFormat)) return emptyList()
        return pattern.findAll(text).mapNotNull { match ->
            val start = match.range.first
            var end = match.range.last
            // Brackets are counted once and adjusted as characters are trimmed: linear, however many trail.
            var open = 0; var close = 0; var openSq = 0; var closeSq = 0
            for (i in start..end) when (text[i]) { '(' -> open++; ')' -> close++; '[' -> openSq++; ']' -> closeSq++ }
            while (end >= start) {
                val c = text[end]
                val unbalancedClose = (c == ')' && open < close) || (c == ']' && openSq < closeSq)
                if (c in TRAILING || unbalancedClose) {
                    when (c) { '(' -> open--; ')' -> close--; '[' -> openSq--; ']' -> closeSq-- }
                    end--
                } else break
            }
            val body = text.substring(start, end + 1)
            // After trimming, the scheme or "www." must still be there with something behind it.
            val lead = prefix.find(body) ?: return@mapNotNull null
            if (body.length == lead.value.length) return@mapNotNull null
            Link(start..end, if (lead.value.startsWith("www.", ignoreCase = true)) "https://$body" else body)
        }.toList()
    }
}
