package org.example.app.core.chats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatTimelineTest {
    private val zone: ZoneId = ZoneOffset.UTC

    private fun at(day: String, time: String = "10:00:00") = Instant.parse("${day}T${time}Z")

    private fun m(id: String, at: Instant, sender: String = "a", content: MessageContent = MessageContent.Text(id)) =
        Message(id, sender, at, content, DeliveryStatus.Read, null, emptyList())

    @Test fun twoMessagesOnOneDayShareOneDateRow() {
        val a = m("a", at("2026-11-17")); val b = m("b", at("2026-11-17", "11:00:00"))
        assertEquals(
            listOf(ChatRow.DateRow(LocalDate.parse("2026-11-17")), ChatRow.MessageRow(a), ChatRow.MessageRow(b)),
            ChatTimeline.rows(listOf(a, b), null, zone),
        )
    }

    @Test fun midnightStartsANewDay() {
        val a = m("a", at("2026-11-17", "23:59:59")); val b = m("b", at("2026-11-18", "00:00:00"))
        assertEquals(
            listOf(
                ChatRow.DateRow(LocalDate.parse("2026-11-17")), ChatRow.MessageRow(a),
                ChatRow.DateRow(LocalDate.parse("2026-11-18")), ChatRow.MessageRow(b),
            ),
            ChatTimeline.rows(listOf(a, b), null, zone),
        )
    }

    @Test fun theZoneDecidesWhereMidnightIs() {
        val a = m("a", at("2026-11-17", "23:30:00")); val b = m("b", at("2026-11-18", "00:30:00"))
        // Both fall on the 18th in UTC+2: one date row and two messages.
        assertEquals(3, ChatTimeline.rows(listOf(a, b), null, ZoneOffset.ofHours(2)).size)
    }

    @Test fun unreadMarkerSitsBetweenTheDateRowAndItsMessage() {
        val a = m("a", at("2026-11-17")); val b = m("b", at("2026-11-18"))
        assertEquals(
            listOf(
                ChatRow.DateRow(LocalDate.parse("2026-11-17")), ChatRow.MessageRow(a),
                ChatRow.DateRow(LocalDate.parse("2026-11-18")), ChatRow.UnreadMarker, ChatRow.MessageRow(b),
            ),
            ChatTimeline.rows(listOf(a, b), "b", zone),
        )
    }

    @Test fun unknownUnreadIdGivesNoMarker() {
        val rows = ChatTimeline.rows(listOf(m("a", at("2026-11-17"))), "zzz", zone)
        assertEquals(2, rows.size)
        assertEquals(false, rows.contains(ChatRow.UnreadMarker))
    }

    @Test fun emptyInputGivesNoRows() = assertEquals(emptyList(), ChatTimeline.rows(emptyList(), "a", zone))

    @Test fun dayLabels() {
        val today = LocalDate.parse("2026-11-18")
        assertEquals(DayLabel.Today, ChatTimeline.dayLabel(today, today))
        assertEquals(DayLabel.Yesterday, ChatTimeline.dayLabel(today.minusDays(1), today))
        assertEquals(DayLabel.Date, ChatTimeline.dayLabel(today.minusDays(2), today))
        assertEquals(DayLabel.Date, ChatTimeline.dayLabel(today.plusDays(1), today))
    }

    @Test fun firstUnreadCountsOnlyIncomingMessagesFromTheNewestEnd() {
        val list = listOf(
            m("1", at("2026-11-17"), "riya"), m("2", at("2026-11-17"), "riya"),
            m("3", at("2026-11-17"), "me"), m("4", at("2026-11-17"), "riya"),
        )
        assertEquals("2", ChatTimeline.firstUnreadId(list, unreadCount = 2, myId = "me"))
        assertEquals("4", ChatTimeline.firstUnreadId(list, unreadCount = 1, myId = "me"))
        assertNull(ChatTimeline.firstUnreadId(list, unreadCount = 0, myId = "me"))
        // More unread than loaded: no marker is better than a wrong one.
        assertNull(ChatTimeline.firstUnreadId(list, unreadCount = 9, myId = "me"))
    }

    @Test fun callRecordsAndSystemLinesDoNotCountAsUnread() {
        val list = listOf(
            m("1", at("2026-11-17"), "riya"), m("2", at("2026-11-17"), "riya"),
            m("3", at("2026-11-17"), "riya", MessageContent.Call(CallSummary.MissedAudio)),
            m("4", at("2026-11-17"), "system", MessageContent.System(SystemEvent.SafetyNumberChanged)),
        )
        assertEquals("1", ChatTimeline.firstUnreadId(list, unreadCount = 2, myId = "me"))
    }

    // Review focus 2: pages and live updates overlap; there must be one copy of each message.
    @Test fun mergeKeepsOneCopyPerIdAndSortsOldestFirst() {
        val a = m("a", at("2026-11-15")); val b = m("b", at("2026-11-16")); val c = m("c", at("2026-11-17"))
        val edited = c.copy(editedAt = at("2026-11-18"))
        // `older` was loaded earlier and overlaps; the current message wins.
        val merged = ChatTimeline.merge(older = listOf(a, b, c), current = listOf(b, edited))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals(edited, merged.last())
    }

    @Test fun mergeOrdersEqualTimesById() {
        val t = at("2026-11-15")
        assertEquals(listOf("a", "b"), ChatTimeline.merge(listOf(m("b", t)), listOf(m("a", t))).map { it.id })
    }

    @Test fun unseenCountsMessagesNewerThanTheLastSeenOne() {
        val list = listOf(m("1", at("2026-11-15")), m("2", at("2026-11-16")), m("3", at("2026-11-17")))
        assertEquals(2, ChatTimeline.unseenCount(list, lastSeenNewestId = "1"))
        assertEquals(0, ChatTimeline.unseenCount(list, lastSeenNewestId = "3"))
        assertEquals(0, ChatTimeline.unseenCount(list, lastSeenNewestId = null))
        assertEquals(0, ChatTimeline.unseenCount(list, lastSeenNewestId = "gone"))
    }
}
