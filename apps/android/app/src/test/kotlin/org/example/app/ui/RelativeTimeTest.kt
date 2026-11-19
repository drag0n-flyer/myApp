package org.example.app.ui

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelativeTimeTest {
    private val zone = ZoneId.of("UTC")
    // Wednesday 2026-11-18 15:00.
    private val now = Instant.parse("2026-11-18T15:00:00Z")

    // Newer ICU data puts a narrow no-break space before AM/PM; older data a plain space.
    private fun tidy(s: String) = s.replace(' ', ' ')

    private fun fmt(at: String, locale: Locale = Locale.US) = tidy(relativeTime(Instant.parse(at), now, zone, locale, "Yesterday"))

    @Test fun todayShowsTheTimeInTheLocalesClock() {
        assertEquals("9:05 AM", fmt("2026-11-18T09:05:00Z"))
        assertEquals("09:05", fmt("2026-11-18T09:05:00Z", Locale.GERMANY))
    }

    @Test fun yesterdayUsesTheLabelFromJustAfterMidnightToJustBefore() {
        assertEquals("Yesterday", fmt("2026-11-17T23:59:00Z"))
        assertEquals("Yesterday", fmt("2026-11-17T00:01:00Z"))
    }

    @Test fun lastSixDaysShowTheWeekday() {
        assertEquals("Monday", fmt("2026-11-16T10:00:00Z"))
        assertEquals("Thursday", fmt("2026-11-12T10:00:00Z"))
        assertEquals("Montag", fmt("2026-11-16T10:00:00Z", Locale.GERMANY))
    }

    @Test fun olderShowsAShortDate() {
        assertEquals("11/11/26", fmt("2026-11-11T10:00:00Z"))
        assertEquals("11.11.26", fmt("2026-11-11T10:00:00Z", Locale.GERMANY))
    }

    @Test fun usesTheGivenZoneForDayBoundaries() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        // 14:30 UTC on the 18th is 23:30 on the 18th in Tokyo, where it is already the 19th: yesterday there, today in UTC.
        val at = Instant.parse("2026-11-18T14:30:00Z")
        assertEquals("Yesterday", relativeTime(at, now, tokyo, Locale.US, "Yesterday"))
        assertEquals("2:30 PM", tidy(relativeTime(at, now, zone, Locale.US, "Yesterday")))
    }
}
