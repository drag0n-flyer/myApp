package org.example.app.core.groups

import org.example.app.core.chats.ChatRow
import org.example.app.core.chats.MessageContent

/** Which incoming messages of a group chat carry the sender's name and tile: the first of each run by one member. */
object GroupRuns {
    /**
     * The IDs of the messages (from [rows], oldest first) that start a run of messages from one other member. A date
     * line, the unread line, a system line, another sender or my own message before it ends the run before it.
     */
    fun senderLineIds(rows: List<ChatRow>, me: String): Set<String> {
        val out = LinkedHashSet<String>()
        var runOf: String? = null
        for (row in rows) {
            if (row !is ChatRow.MessageRow) {
                runOf = null
                continue
            }
            val message = row.message
            if (message.content is MessageContent.System || message.senderId == me) {
                runOf = null
                continue
            }
            if (message.senderId != runOf) out += message.id
            runOf = message.senderId
        }
        return out
    }
}
