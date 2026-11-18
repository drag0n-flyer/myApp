package org.example.app.ui.lock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.app.core.settings.UserSettings

class AppLockTest {
    private var now = 1_000L
    private var settings = UserSettings(appLock = true, lockAfterSeconds = 60)
    private fun lock() = AppLock({ settings }, { now })

    @Test fun startsLockedWhenTheSettingIsOnAsAfterProcessDeath() = assertTrue(lock().locked.value)

    @Test fun startsUnlockedWhenTheSettingIsOff() {
        settings = UserSettings()
        assertFalse(lock().locked.value)
    }

    @Test fun theFirstPromptIsOfferedOnceAndNotAgainOnARotation() {
        val l = lock()
        assertTrue(l.takePrompt())
        assertFalse(l.takePrompt())
        l.onStart() // the recreated activity: no stop came before
        assertFalse(l.takePrompt())
        assertTrue(l.locked.value)
    }

    @Test fun returningAfterTheDelayLocksAndAsksAgainOnce() {
        val l = lock()
        l.takePrompt(); l.unlock()
        l.onStop()
        now += 61_000
        l.onStart()
        assertTrue(l.locked.value)
        assertTrue(l.takePrompt())
        assertFalse(l.takePrompt())
    }

    @Test fun returningSoonerKeepsItUnlockedAndAsksNothing() {
        val l = lock()
        l.takePrompt(); l.unlock()
        l.onStop()
        now += 10_000
        l.onStart()
        assertFalse(l.locked.value)
        assertFalse(l.takePrompt())
    }

    @Test fun immediatelyLocksAtOnce() {
        settings = UserSettings(appLock = true, lockAfterSeconds = 0)
        val l = lock()
        l.unlock()
        l.onStop()
        assertTrue(l.locked.value)
    }

    @Test fun aClockThatWentBackwardsLocks() {
        val l = lock()
        l.unlock()
        l.onStop()
        now -= 5_000
        l.onStart()
        assertTrue(l.locked.value)
    }

    @Test fun theDelayIsReadFromTheSettingsAtTheTime() {
        val l = lock()
        l.unlock()
        settings = settings.copy(lockAfterSeconds = 300)
        l.onStop()
        now += 120_000
        l.onStart()
        assertFalse(l.locked.value)
    }

    @Test fun anOddStoredDelayDoesNotDisableTheLock() {
        settings = UserSettings(appLock = true, lockAfterSeconds = 99_999)
        val l = lock()
        l.unlock()
        l.onStop()
        now += 61_000
        l.onStart()
        assertTrue(l.locked.value)
    }

    @Test fun switchingItOnLeavesTheAppUnlockedAndOffStaysUnlocked() {
        settings = UserSettings()
        val l = lock()
        settings = settings.copy(appLock = true)
        l.setEnabled(true)
        assertFalse(l.locked.value)
        l.setEnabled(false)
        l.onStop()
        now += 1_000_000
        l.onStart()
        assertEquals(false, l.locked.value)
    }
}
