package org.example.app.ui.chat

import java.time.Duration
import org.example.app.core.chats.ChatRow
import org.example.app.core.chats.MessageContent

/** Which bubbles are the first and last of a run by one sender, so a run reads as one block with a single tail. */
object BubbleRuns {
    data class Position(val first: Boolean, val last: Boolean)

    private val MAX_GAP: Duration = Duration.ofMinutes(5)

    /** [rows] oldest first. A date line, the unread line, a system line, another sender or a gap over five minutes ends a run. */
    fun positions(rows: List<ChatRow>): Map<String, Position> {
        val ids = ArrayList<String>()
        val joined = ArrayList<Boolean>() // joined[i]: message i continues the run of message i-1
        var prev: ChatRow.MessageRow? = null
        for (row in rows) {
            if (row !is ChatRow.MessageRow || row.message.content is MessageContent.System) {
                prev = null
                continue
            }
            val p = prev
            joined += p != null && p.message.senderId == row.message.senderId &&
                Duration.between(p.message.sentAt, row.message.sentAt).abs() <= MAX_GAP
            ids += row.message.id
            prev = row
        }
        return ids.indices.associate { i ->
            ids[i] to Position(first = !joined[i], last = i == ids.lastIndex || !joined[i + 1])
        }
    }
}
