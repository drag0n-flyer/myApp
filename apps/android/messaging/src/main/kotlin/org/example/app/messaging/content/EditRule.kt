package org.example.app.messaging.content

/**
 * What an edit does to a message, the same on the sender's device and the receiver's. A text message gets new text; a
 * photo or file that has a caption gets a new caption; a photo or file without one is not edited. Everything else in the
 * envelope (reply, timer, attachment) stays. A link preview stays while its link is still in the text and goes otherwise.
 */
object EditRule {
    class Result(val envelope: ContentEnvelope, val folded: String, val caption: String?, val linkPreview: String?)

    /** Null when the message cannot be edited. [fold] makes the search text. */
    fun apply(old: ContentEnvelope, text: String, fold: (String) -> String): Result? = when (old) {
        is ContentEnvelope.Text -> {
            val lp = old.lp?.takeIf { it.url in text }
            Result(old.copy(t = text, lp = lp), fold(text), null, lp?.let(ContentCodec::linkPreviewJson))
        }
        is ContentEnvelope.Photo -> if (old.c == null) null else {
            val c = TextLimit.truncate(text)
            Result(ContentEnvelope.Photo(old.id, old.at, old.a, old.w, old.h, old.th, old.r, old.v, old.ttl, c), fold(c), c, null)
        }
        is ContentEnvelope.File -> if (old.c == null) null else {
            val c = TextLimit.truncate(text)
            Result(old.copy(c = c), fold(listOf(old.name, c).joinToString(" ")), c, null)
        }
        else -> null
    }
}
