package org.example.app.core.profile

/**
 * What a display name may be. The server accepts up to 64 characters with no control characters; this is stricter:
 * 1 to 40 characters after trimming, at least one visible character, no line or paragraph breaks, no control
 * characters, no direction or format controls (they can make a name read as something else), no broken surrogate
 * pairs. [forDisplay] cleans a name that came from somewhere else.
 */
object DisplayNameRule {
    const val MAX = 40

    /** The longest name the server stores. */
    private const val SERVER_MAX = 64

    private val directionControls = setOf(0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0x200E, 0x200F, 0x061C)

    /** Letters or fillers that draw nothing (zero-width and Hangul/Braille blanks): they do not count as a visible character. */
    private val invisible = setOf(0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x3164, 0x115F, 0x1160, 0xFFA0, 0x2800, 0x034F, 0x180E, 0x17B4, 0x17B5)

    /** The trimmed name, or null when it is not acceptable. */
    fun clean(input: String): String? {
        val name = trimmed(input)
        if (name.isEmpty()) return null
        var count = 0
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            i += Character.charCount(cp)
            if (++count > MAX) return null
            if (cp in directionControls) return null
            when (Character.getType(cp)) {
                Character.CONTROL.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
                Character.SURROGATE.toInt() -> return null
            }
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
            if (cp in directionControls) continue
            when (Character.getType(cp)) {
                Character.CONTROL.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
                Character.SURROGATE.toInt() -> continue
            }
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
                Character.OTHER_SYMBOL.toInt() -> return true
            }
        }
        return false
    }
}
