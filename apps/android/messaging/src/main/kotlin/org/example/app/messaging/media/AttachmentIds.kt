package org.example.app.messaging.media

/**
 * The one rule for an attachment id, whoever made it: another person's message or the server's answer to an upload. An
 * id becomes part of a file name, so it must never be able to become a path.
 */
object AttachmentIds {
    private val ID = Regex("[A-Za-z0-9_-]{1,128}")

    fun isValid(id: String): Boolean = ID.matches(id)

    /** Throws [IllegalArgumentException] for an id that is not 1 to 128 of letters, digits, `_` and `-`. */
    fun check(id: String) {
        require(isValid(id)) { "bad attachment id" }
    }
}
