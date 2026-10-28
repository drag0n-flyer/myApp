package org.example.app.ui.qr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraBinderTest {
    private val log = mutableListOf<String>()
    private var failure: Throwable? = null
    private fun binder(bindThrows: Boolean = false) = CameraBinder<String>(
        bind = { log += "bind $it"; if (bindThrows) error("camera busy") },
        unbind = { log += "unbind $it" },
        onFailure = { failure = it },
    )

    @Test fun bindsWhenTheProviderIsReadyAndUnbindsOnRelease() {
        val b = binder()
        b.ready("p")
        b.release()
        assertEquals(listOf("bind p", "unbind p"), log)
    }

    @Test fun neverBindsAfterRelease() {
        val b = binder()
        b.release()
        b.ready("late")
        assertTrue(log.isEmpty())
    }

    @Test fun releaseTwiceUnbindsOnce() {
        val b = binder()
        b.ready("p"); b.release(); b.release()
        assertEquals(listOf("bind p", "unbind p"), log)
    }

    @Test fun aBindFailureIsReportedNotThrownAndStillCleansUp() {
        val b = binder(bindThrows = true)
        b.ready("p")
        assertEquals("camera busy", failure?.message)
        b.release()
        assertTrue("unbind p" in log)
    }

    @Test fun aProviderFailureIsReportedOnlyWhileActive() {
        val b = binder()
        b.failed(IllegalStateException("no camera"))
        assertEquals("no camera", failure?.message)
        failure = null
        b.release()
        b.failed(IllegalStateException("after"))
        assertEquals(null, failure)
    }

    @Test fun anUnbindFailureDoesNotEscape() {
        val b = CameraBinder<String>({}, { error("boom") }, { failure = it })
        b.ready("p")
        b.release()
    }
}
