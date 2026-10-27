package org.example.app.messaging.content

import java.text.BreakIterator

/** Text and edit content is limited to 4096 grapheme clusters, truncated at compose time (as the iPhone's `prefix(4096)`). */
object TextLimit {
    const val MAX_GRAPHEMES = 4096
    private const val ZWJ = '‍'

    /**
     * Android's ICU `BreakIterator` keeps emoji ZWJ sequences whole; older JDKs (the unit-test JVM) split around
     * the joiner, so a break next to U+200D is skipped here to behave the same on both.
     */
    fun truncate(s: String, max: Int = MAX_GRAPHEMES): String {
        if (s.length <= max) return s // a grapheme is at least one UTF-16 unit
        val bi = BreakIterator.getCharacterInstance()
        bi.setText(s)
        var end = 0
        repeat(max) {
            var n = bi.next()
            while (n != BreakIterator.DONE && n < s.length && (s[n - 1] == ZWJ || s[n] == ZWJ)) n = bi.next()
            if (n == BreakIterator.DONE) return s
            end = n
        }
        return s.substring(0, end)
    }
}
