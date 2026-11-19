package org.example.app.ui.chat

import java.time.Instant
import kotlin.test.assertEquals
import org.example.app.core.chats.ChatRow
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.junit.Test

class BubbleRunsTest {
    private val t0 = Instant.parse("2026-11-19T10:00:00Z")

    private fun row(id: String, sender: String, minutes: Long) = ChatRow.MessageRow(
        Message(id, sender, t0.plusSeconds(minutes * 60), MessageContent.Text("hi"), DeliveryStatus.Sent, null, emptyList()),
    )

    @Test fun `a run of one sender tails only its last bubble`() {
        val p = BubbleRuns.positions(listOf(row("1", "a", 0), row("2", "a", 1), row("3", "a", 2)))
        assertEquals(BubbleRuns.Position(first = true, last = false), p["1"])
        assertEquals(BubbleRuns.Position(first = false, last = false), p["2"])
        assertEquals(BubbleRuns.Position(first = false, last = true), p["3"])
    }

    @Test fun `another sender, a gap of minutes, or a non-message row ends the run`() {
        val rows = listOf(row("1", "a", 0), row("2", "b", 1), row("3", "b", 30), row("4", "b", 31), ChatRow.UnreadMarker, row("5", "b", 32))
        val p = BubbleRuns.positions(rows)
        assertEquals(BubbleRuns.Position(true, true), p["1"])
        assertEquals(BubbleRuns.Position(true, true), p["2"])
        assertEquals(BubbleRuns.Position(true, false), p["3"])
        assertEquals(BubbleRuns.Position(false, true), p["4"])
        assertEquals(BubbleRuns.Position(true, true), p["5"])
    }
}
