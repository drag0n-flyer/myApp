package org.example.app.core.profile

/**
 * What a display name may be. The server accepts up to 64 characters with no control characters; this is stricter:
 * 1 to 40 code points after trimming, at least one visible character, no line or paragraph breaks, no control
 * characters, no format characters (they can make a name read as something else; listed by code point, plus every
 * other Cf one) except U+200C, U+200D and the tag characters U+E0020..U+E007F that emoji sequences need, no broken
 * surrogate pairs. Unassigned code points count as visible, so the Unicode table version of a platform does not
 * change the result. The same rule as the server's and AppCore's. [forDisplay] cleans a name that came from somewhere else.
 */
object DisplayNameRule {
    const val MAX = 40

    /** The longest name the server stores. */
    private const val SERVER_MAX = 64

    /** Format and direction characters refused by code point, whatever the platform's tables say. */
    private val refusedRanges = listOf(
        0x00AD..0x00AD, 0x0600..0x0605, 0x061C..0x061C, 0x06DD..0x06DD, 0x070F..0x070F, 0x0890..0x0891,
        0x08E2..0x08E2, 0x180E..0x180E, 0x200B..0x200B, 0x200E..0x200F, 0x202A..0x202E, 0x2060..0x2064,
        0x2066..0x206F, 0xFEFF..0xFEFF, 0xFFF9..0xFFFB, 0x110BD..0x110BD, 0x110CD..0x110CD,
        0x13430..0x1343F, 0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0001..0xE0001,
    )

    private fun isRefused(cp: Int): Boolean {
        if (refusedRanges.any { cp in it }) return true
        return when (Character.getType(cp)) {
            Character.CONTROL.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
            Character.SURROGATE.toInt() -> true
            Character.FORMAT.toInt() -> !(cp == 0x200C || cp == 0x200D || cp in 0xE0020..0xE007F)
            else -> false
        }
    }

    /** Letters or fillers that draw nothing (zero-width and Hangul/Braille blanks): they do not count as a visible character. */
    private val invisible = setOf(0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x3164, 0x115F, 0x1160, 0xFFA0, 0x2800, 0x034F, 0x180E, 0x17B4, 0x17B5)

    /** The trimmed name, or null when it is not acceptable. */
    fun clean(input: String): String? = cleanUpTo(input, MAX)

    /** The same character rules with another length limit in code points (a group name allows 50). */
    fun cleanUpTo(input: String, max: Int): String? {
        val name = trimmed(input)
        if (name.isEmpty()) return null
        var count = 0
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            i += Character.charCount(cp)
            if (++count > max) return null
            if (isRefused(cp)) return null
        }
        return if (hasVisible(name)) name else null
    }

    /**
     * A name for showing, from data this device did not check (the server only refuses control characters): direction
     * and format controls, line and paragraph breaks and control characters removed, trimmed, cut to 64 code points;
     * [fallback] when nothing visible is left.
     */
    fun forDisplay(name: String, fallback: String): String {
        val kept = StringBuilder()
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            i += Character.charCount(cp)
            if (isRefused(cp)) continue
            kept.appendCodePoint(cp)
        }
        val trimmed = trimmed(kept.toString())
        val cut = if (trimmed.codePointCount(0, trimmed.length) > SERVER_MAX) {
            trimmed(trimmed.substring(0, trimmed.offsetByCodePoints(0, SERVER_MAX)))
        } else trimmed
        return if (hasVisible(cut)) cut else fallback
    }

    private fun trimmed(s: String) = s.trim { Character.isWhitespace(it) || Character.isSpaceChar(it) }

    private fun hasVisible(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in invisible) continue
            when (Character.getType(cp)) {
                Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt(),
                Character.MODIFIER_LETTER.toInt(), Character.OTHER_LETTER.toInt(),
                Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
                Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
                Character.MATH_SYMBOL.toInt(), Character.CURRENCY_SYMBOL.toInt(), Character.MODIFIER_SYMBOL.toInt(),
                Character.OTHER_SYMBOL.toInt(), Character.UNASSIGNED.toInt() -> return true
            }
        }
        return false
    }
}
