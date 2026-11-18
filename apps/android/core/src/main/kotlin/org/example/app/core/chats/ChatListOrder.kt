package org.example.app.core.chats

import java.time.Instant

/** How the chat list is ordered and filtered. Pure, so the rules are tested on the JVM. */
object ChatListOrder {
    const val MAX_PINS = 5

    private val byRecency = compareByDescending<Chat> { it.lastMessage?.sentAt ?: Instant.MIN }.thenBy { it.id }
    private val byPin = compareByDescending<Chat> { it.pinnedAt ?: Instant.MIN }.thenBy { it.id }

    /** Pinned chats first, the newest pin on top; then the rest by last message, newest first. */
    fun sorted(chats: List<Chat>): List<Chat> {
        val (pinned, rest) = chats.partition { it.pinnedAt != null }
        return pinned.sortedWith(byPin) + rest.sortedWith(byRecency)
    }

    /** The main list (`archive = false`) or the archive (`archive = true`). */
    fun visible(chats: List<Chat>, archive: Boolean): List<Chat> = chats.filter { it.archived == archive }

    /** Chats with something unread that are neither muted nor archived. */
    fun badgeCount(chats: List<Chat>): Int = chats.count { it.unreadCount > 0 && !it.muted && !it.archived }
}
