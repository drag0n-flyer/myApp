package org.example.app.ui.media

/**
 * Turns a name that came from another app (the document picker) or from a received message into one plain file name.
 * Such a name is never trusted: it can carry directories, control or direction-changing characters, or be endless.
 */
object SafeFileName {
    const val MAX_LENGTH = 100
    private const val FORBIDDEN = ":*?\"<>|"

    /** A single file name, never a path, never `.` or `..`, at most [MAX_LENGTH] characters; [fallback] when nothing is left. */
    fun of(raw: String?, fallback: String): String {
        if (raw == null) return fallback
        var name = raw.substringAfterLast('/').substringAfterLast('\\')
        name = name.filter { c ->
            c !in FORBIDDEN && when (Character.getType(c)) {
                // Control, format (right-to-left override and friends) and line or paragraph separators.
                Character.CONTROL.toInt(), Character.FORMAT.toInt(),
                Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> false
                else -> true
            }
        }
        name = name.trim { it == ' ' || it == '.' }
        if (name.length > MAX_LENGTH) {
            val ext = name.substringAfterLast('.', "")
            name = if (ext.isNotEmpty() && ext.length <= MAX_EXTENSION) {
                shorten(name.substring(0, name.length - ext.length - 1), MAX_LENGTH - ext.length - 1) + "." + ext
            } else {
                shorten(name, MAX_LENGTH)
            }
            name = name.trim { it == ' ' || it == '.' }
        }
        return name.ifEmpty { fallback }
    }

    private const val MAX_EXTENSION = 10

    private fun shorten(text: String, length: Int): String {
        val cut = text.take(length)
        // Never end on half of a surrogate pair.
        return if (cut.isNotEmpty() && Character.isHighSurrogate(cut.last())) cut.dropLast(1) else cut
    }
}
