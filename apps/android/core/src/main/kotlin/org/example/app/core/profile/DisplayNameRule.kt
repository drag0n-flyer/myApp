package org.example.app.core.profile

/**
 * What a display name may be. The server accepts up to 64 characters with no control characters; this is stricter:
 * 1 to 40 characters after trimming, no line or paragraph breaks, no control characters, no direction or format
 * controls (they can make a name read as something else), no broken surrogate pairs.
 */
object DisplayNameRule {
    const val MAX = 40

    private val directionControls = setOf(0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0x200E, 0x200F, 0x061C)

    /** The trimmed name, or null when it is not acceptable. */
    fun clean(input: String): String? {
        val name = input.trim { Character.isWhitespace(it) || Character.isSpaceChar(it) }
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
                Character.SURROGATE.toInt(), Character.UNASSIGNED.toInt() -> return null
            }
        }
        return name
    }

    /** A name for showing, from data this device did not check (the server only refuses control characters). */
    fun forDisplay(name: String, fallback: String): String = name
}
