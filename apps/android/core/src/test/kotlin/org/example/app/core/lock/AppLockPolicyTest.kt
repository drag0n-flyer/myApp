package org.example.app.core.lock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockPolicyTest {
    private fun unlockedPolicy(seconds: Int): AppLockPolicy = AppLockPolicy(seconds).also { it.unlocked() }

    @Test fun startsLockedWhenOn() = assertTrue(AppLockPolicy(60).locked)

    @Test fun startsUnlockedWhenOff() = assertFalse(AppLockPolicy(60, enabled = false).locked)

    @Test fun unlockedClearsTheLock() {
        val p = AppLockPolicy(60)
        p.unlocked()
        assertFalse(p.locked)
    }

    @Test fun immediatelyLocksOnEveryReturn() {
        val p = unlockedPolicy(0)
        repeat(3) { i ->
            val t = 1_000L * (i + 1)
            p.onBackground(t)
            assertTrue(p.onForeground(t))
            assertTrue(p.locked)
            p.unlocked()
        }
    }

    @Test fun immediatelyLocksAtTheMomentOfLeaving() {
        val p = unlockedPolicy(0)
        p.onBackground(1_000)
        assertTrue(p.locked) // before the app returns, nothing may be waiting behind the screen
    }

    @Test fun oneMinuteStaysUnlockedAt59SecondsAndLocksAt60() {
        val a = unlockedPolicy(60)
        a.onBackground(10_000)
        assertFalse(a.onForeground(10_000 + 59_000))
        assertFalse(a.locked)
        val b = unlockedPolicy(60)
        b.onBackground(10_000)
        assertTrue(b.onForeground(10_000 + 60_000))
    }

    @Test fun aBackwardsClockJumpLocks() {
        val p = unlockedPolicy(3600)
        p.onBackground(50_000)
        assertTrue(p.onForeground(49_999))
    }

    @Test fun aLockedAppStaysLockedWhateverTheClockSays() {
        val p = AppLockPolicy(3600)
        p.onBackground(0)
        assertTrue(p.onForeground(1))
    }

    @Test fun returningWithoutLeavingChangesNothing() {
        val p = unlockedPolicy(0)
        assertFalse(p.onForeground(5_000)) // a rotation: no onBackground
        assertFalse(p.locked)
    }

    @Test fun theTimeAwayCountsFromTheFirstLeave() {
        val p = unlockedPolicy(60)
        p.onBackground(0)
        p.onBackground(50_000)
        assertTrue(p.onForeground(60_000))
    }

    @Test fun eachAbsenceIsMeasuredOnItsOwn() {
        val p = unlockedPolicy(60)
        p.onBackground(0)
        assertFalse(p.onForeground(30_000))
        p.onBackground(100_000)
        assertFalse(p.onForeground(130_000))
    }

    @Test fun turningItOffUnlocksAndNothingLocksAfter() {
        val p = AppLockPolicy(0)
        assertTrue(p.locked)
        p.setEnabled(false)
        assertFalse(p.locked)
        p.onBackground(0)
        assertFalse(p.onForeground(1))
    }

    @Test fun turningItOnLeavesTheAppUnlockedForNow() {
        val p = AppLockPolicy(60, enabled = false)
        p.setEnabled(true)
        assertFalse(p.locked)
        p.onBackground(0)
        assertTrue(p.onForeground(60_000))
    }

    @Test fun anOddDelayIsTreatedAsTheDefault() {
        assertEquals(60, AppLockPolicy.validDelay(7))
        assertEquals(0, AppLockPolicy.validDelay(0))
        assertEquals(3600, AppLockPolicy.validDelay(3600))
        assertEquals(60, AppLockPolicy.validDelay(-1))
    }
}
