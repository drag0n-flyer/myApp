package org.example.app.core.chats

/** Moving through the results of a search inside one chat. Results are newest first; "Next" goes to older ones. */
object ChatSearch {
    enum class Direction { Next, Previous }

    /** The index to show after a move, wrapping at both ends; null when there are no results. [current] null selects the newest. */
    fun step(results: List<MessageHit>, current: Int?, direction: Direction): Int? {
        if (results.isEmpty()) return null
        if (current == null || current !in results.indices) return 0
        val last = results.size - 1
        return when (direction) {
            Direction.Next -> if (current == last) 0 else current + 1
            Direction.Previous -> if (current == 0) last else current - 1
        }
    }

    /** Where a message is in [results], so the selection survives a new result list. */
    fun indexOf(results: List<MessageHit>, messageId: String?): Int? =
        results.indexOfFirst { it.messageId == messageId }.takeIf { it >= 0 }
}
