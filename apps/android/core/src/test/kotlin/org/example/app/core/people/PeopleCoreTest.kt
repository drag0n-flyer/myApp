package org.example.app.core.people

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpiryTest {
    @Test fun expiresAtAddsSecondsInMilliseconds() {
        assertEquals(1_000L + 86_400_000L, Expiry.expiresAt(1_000L, 86_400))
    }

    @Test fun noTtlMeansNoExpiry() {
        assertNull(Expiry.expiresAt(1_000L, null))
        assertNull(Expiry.expiresAt(1_000L, 0))
        assertNull(Expiry.expiresAt(1_000L, -5))
    }

    @Test fun ttlOutOfRangeIsIgnored() {
        assertNull(Expiry.expiresAt(1_000L, 31_622_401))
        assertEquals(1_000L + 31_622_400_000L, Expiry.expiresAt(1_000L, 31_622_400))
        assertEquals(1_000L + 1_000L, Expiry.expiresAt(1_000L, 1))
    }

    @Test fun expiredWhenNowIsAtOrAfter() {
        assertFalse(Expiry.isExpired(null, Long.MAX_VALUE))
        assertFalse(Expiry.isExpired(10_000L, 9_999L))
        assertTrue(Expiry.isExpired(10_000L, 10_000L))
        assertTrue(Expiry.isExpired(10_000L, 10_001L))
    }
}

class TimerChoiceTest {
    @Test fun secondsAreTheWireValues() {
        assertEquals(listOf(0, 86_400, 604_800, 7_776_000), TimerChoice.entries.map { it.seconds })
    }

    @Test fun fromSecondsOnlyKnowsTheFour() {
        assertEquals(TimerChoice.Off, TimerChoice.from(0))
        assertEquals(TimerChoice.Day, TimerChoice.from(86_400))
        assertEquals(TimerChoice.Week, TimerChoice.from(604_800))
        assertEquals(TimerChoice.Quarter, TimerChoice.from(7_776_000))
        assertNull(TimerChoice.from(1))
        assertNull(TimerChoice.from(-86_400))
        assertNull(TimerChoice.from(86_401))
    }
}

class ProfileMergeTest {
    @Test fun onlyStrictlyHigherWins() {
        assertTrue(ProfileMerge.accept(null, 1))
        assertTrue(ProfileMerge.accept(3, 4))
        assertFalse(ProfileMerge.accept(3, 3))
        assertFalse(ProfileMerge.accept(3, 2))
    }

    @Test fun versionBelowOneIsNeverAccepted() {
        assertFalse(ProfileMerge.accept(null, 0))
        assertFalse(ProfileMerge.accept(null, -1))
    }
}
