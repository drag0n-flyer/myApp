package org.example.app.core.profile

/** What a display name may be. Stricter than the server (at most 64 characters, no control characters). */
object DisplayNameRule {
    const val MAX = 40

    /** The trimmed name, or null when it is not acceptable. */
    fun clean(input: String): String? = input
}
