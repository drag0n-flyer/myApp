package org.example.app.core.chats

import java.time.LocalDate
import java.time.ZoneId

/** One line of the chat list: a date chip, the unread line, or a message. */
sealed interface ChatRow {
    /** Stable across rebuilds, for list keys. */
    val key: String

    data class DateRow(val day: LocalDate) : ChatRow {
        override val key get() = "date-$day"
    }

    data object UnreadMarker : ChatRow {
        override val key get() = "unread-marker"
    }

    data class MessageRow(val message: Message) : ChatRow {
        override val key get() = message.id
    }
}

enum class DayLabel { Today, Yesterday, Date }

object ChatTimeline {
    /** [messages] oldest first. A date row precedes the first message of each local day; the marker precedes [firstUnreadId]. */
    fun rows(messages: List<Message>, firstUnreadId: String?, zone: ZoneId): List<ChatRow> {
        val rows = ArrayList<ChatRow>(messages.size + 4)
        var day: LocalDate? = null
        for (message in messages) {
            val d = message.sentAt.atZone(zone).toLocalDate()
            if (d != day) {
                rows += ChatRow.DateRow(d)
                day = d
            }
            if (message.id == firstUnreadId) rows += ChatRow.UnreadMarker
            rows += ChatRow.MessageRow(message)
        }
        return rows
    }

    fun dayLabel(day: LocalDate, today: LocalDate): DayLabel = when (day) {
        today -> DayLabel.Today
        today.minusDays(1) -> DayLabel.Yesterday
        else -> DayLabel.Date
    }

    /**
     * The oldest of the last [unreadCount] incoming messages in [messages] (oldest first), or null when there is nothing
     * unread or fewer incoming messages are loaded than the count (a wrong line is worse than none).
     */
    fun firstUnreadId(messages: List<Message>, unreadCount: Int, myId: String): String? {
        if (unreadCount <= 0) return null
        var seen = 0
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            if (m.senderId == myId || m.content is MessageContent.System) continue
            seen++
            if (seen == unreadCount) return m.id
        }
        return null
    }

    /** Oldest first, one copy per id. For an id in both lists the [current] copy wins (it may have been edited since). */
    fun merge(older: List<Message>, current: List<Message>): List<Message> {
        val byId = LinkedHashMap<String, Message>(older.size + current.size)
        for (m in older) byId[m.id] = m
        for (m in current) byId[m.id] = m
        return byId.values.sortedWith(compareBy<Message> { it.sentAt }.thenBy { it.id })
    }

    /** Messages newer than [lastSeenNewestId] in [messages] (oldest first); zero when that id is null or no longer there. */
    fun unseenCount(messages: List<Message>, lastSeenNewestId: String?): Int {
        if (lastSeenNewestId == null) return 0
        val index = messages.indexOfLast { it.id == lastSeenNewestId }
        return if (index < 0) 0 else messages.size - 1 - index
    }
}
