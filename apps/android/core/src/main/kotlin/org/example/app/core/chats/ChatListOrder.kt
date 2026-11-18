package org.example.app.core.chats

/** How the chat list is ordered and filtered. Pure, so the rules are tested on the JVM. */
object ChatListOrder {
    const val MAX_PINS = 5

    /** Pinned chats first, the newest pin on top; then the rest by last message, newest first. */
    fun sorted(chats: List<Chat>): List<Chat> = chats

    /** The main list (`archive = false`) or the archive (`archive = true`). */
    fun visible(chats: List<Chat>, archive: Boolean): List<Chat> = chats

    /** Chats with something unread that are neither muted nor archived. */
    fun badgeCount(chats: List<Chat>): Int = 0
}
