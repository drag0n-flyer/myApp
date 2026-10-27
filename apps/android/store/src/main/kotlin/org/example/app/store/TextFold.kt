package org.example.app.store

import java.text.Normalizer
import java.util.Locale

/**
 * Folds text for search: width variants, case and accents are ignored.
 *
 * Only accent-like combining marks are removed: the general diacritics blocks plus Hebrew points and Arabic
 * vowel marks. Marks that carry meaning in Indic and Southeast Asian scripts (virama, vowel signs) stay, so
 * "नमस्ते" keeps its letters.
 */
object TextFold {
    private val accentMarks = Regex(
        "[\\u0300-\\u036F\\u0483-\\u0489\\u0591-\\u05BD\\u05BF\\u05C1\\u05C2\\u05C4\\u05C5\\u05C7" +
            "\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06DC\\u06DF-\\u06E4\\u06E7\\u06E8\\u06EA-\\u06ED" +
            "\\u1AB0-\\u1AFF\\u1DC0-\\u1DFF\\u20D0-\\u20FF\\uFE20-\\uFE2F]+",
    )

    fun fold(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFKD)
        val stripped = accentMarks.replace(decomposed, "")
        return Normalizer.normalize(stripped.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
    }
}
