package org.example.app.messaging.content

/**
 * What an edit does to a message, the same on the sender's device and the receiver's. A text message gets new text; a
 * photo or file gets a new caption, one added where there was none, or none at all when the text is blank. Everything else in the
 * envelope (reply, timer, attachment) stays. A link preview stays while its link is still in the text and goes otherwise.
 */
object EditRule {
    class Result(val envelope: ContentEnvelope, val folded: String, val caption: String?, val linkPreview: String?)

    /** Null when the message cannot be edited, including a text message whose new text is blank. [fold] makes the search text. */
    fun apply(old: ContentEnvelope, text: String, fold: (String) -> String): Result? = when (old) {
        is ContentEnvelope.Text -> if (text.isBlank()) null else {
            val lp = old.lp?.takeIf { it.url in text }
            Result(old.copy(t = text, lp = lp), fold(text), null, lp?.let(ContentCodec::linkPreviewJson))
        }
        is ContentEnvelope.Photo -> {
            val c = TextLimit.truncate(text).takeIf { it.isNotBlank() }
            Result(ContentEnvelope.Photo(old.id, old.at, old.a, old.w, old.h, old.th, old.r, old.v, old.ttl, c), fold(c.orEmpty()), c, null)
        }
        is ContentEnvelope.File -> {
            val c = TextLimit.truncate(text).takeIf { it.isNotBlank() }
            Result(old.copy(c = c), fold(listOfNotNull(old.name, c).joinToString(" ")), c, null)
        }
        else -> null
    }
}
