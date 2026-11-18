package org.example.app.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.messaging.ports.Clock

class MonotonicClockTest {
    private class Fake(var now: Long) : Clock { override fun now() = now }

    @Test fun aBurstOnAFixedClockKeepsItsOrder() {
        val c = MonotonicClock(Fake(1_000))
        val times = (1..100).map { c.now() }
        assertEquals(times.sorted().distinct(), times)
    }

    @Test fun aClockSteppedBackAnHourIsFollowed() {
        val fake = Fake(10_000_000)
        val c = MonotonicClock(fake)
        repeat(5) { c.now() }
        fake.now -= 3_600_000
        assertEquals(fake.now, c.now())
    }

    @Test fun manyCallsOnAFixedClockNeverRunMoreThanASecondAheadAndTheClockTakesOverAgain() {
        val fake = Fake(5_000_000)
        val c = MonotonicClock(fake)
        var last = 0L
        repeat(10_000) { last = c.now() }
        assertTrue(last - fake.now <= 1_001, "ahead by ${last - fake.now}")
        fake.now += 5_000
        assertEquals(fake.now, c.now())
    }
}
