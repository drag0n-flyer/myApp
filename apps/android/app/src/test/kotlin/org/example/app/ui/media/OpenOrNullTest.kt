package org.example.app.ui.media

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class OpenOrNullTest {
    private class Opened : OpenedMedia {
        var closed = 0
        override val file = File("/plain/x")
        override fun close() { closed++ }
    }

    @Test fun anOpenedAttachmentIsHandedOverOpen() = runTest {
        val opened = Opened()
        val result = AttachmentOpener { opened }.openOrNull("m", StandardTestDispatcher(testScheduler))
        assertEquals(opened, result)
        assertEquals(0, opened.closed)
    }

    @Test fun aFailureIsNull() = runTest {
        assertNull(AttachmentOpener { throw java.io.IOException("bad") }.openOrNull("m", StandardTestDispatcher(testScheduler)))
        assertNull(AttachmentOpener { null }.openOrNull("m", StandardTestDispatcher(testScheduler)))
    }

    @Test fun anOpenThatFinishesAfterTheCallerWasCancelledIsClosedNotLost() = runTest {
        val gate = CompletableDeferred<Unit>()
        val opened = Opened()
        val opener = AttachmentOpener { gate.await(); opened }
        val job = launch { opener.openOrNull("m", StandardTestDispatcher(testScheduler)) }
        runCurrent()
        job.cancel()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(1, opened.closed)
    }
}
