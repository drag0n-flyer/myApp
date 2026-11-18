package org.example.app.ui.chat

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InfoTimeTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-11-18T12:00:00Z")

    @Test fun todayShowsTheTimeOnly() {
        val text = infoTime(Instant.parse("2026-11-18T09:05:00Z"), now, zone)
        assertEquals(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date.from(Instant.parse("2026-11-18T09:05:00Z"))), text)
    }

    @Test fun anotherDayShowsTheDateAndTheTime() {
        val at = Instant.parse("2026-11-15T09:05:00Z")
        val text = infoTime(at, now, zone)
        val timeOnly = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date.from(at))
        val dateOnly = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date.from(at))
        assertNotEquals(timeOnly, text)
        assertTrue(text.contains(dateOnly) && text.contains(timeOnly.trim()), text)
    }
}
