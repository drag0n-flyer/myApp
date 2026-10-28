package org.example.app.calls.android.media

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RnnoiseTest {
    private class FakeNative : RnnoiseNative {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val inProcess = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun create(): Long = 42L.also { events += "create" }

        override fun process(handle: Long, frame: FloatArray): Float {
            events += "process start $handle"
            inProcess.countDown()
            release.await(5, TimeUnit.SECONDS)
            events += "process end $handle"
            return 0f
        }

        override fun destroy(handle: Long) {
            events += "destroy $handle"
        }
    }

    @Test
    fun closeDuringDenoiseWaitsForTheFrame() {
        val native = FakeNative()
        val rnnoise = Rnnoise(native)
        val worker = thread { rnnoise.denoise(FloatArray(RNNOISE_FRAME_SAMPLES)) }
        assertTrue(native.inProcess.await(5, TimeUnit.SECONDS))
        val closer = thread { rnnoise.close() }
        closer.join(200)
        assertTrue(closer.isAlive, "close must wait for the frame in progress")
        native.release.countDown()
        worker.join(5_000)
        closer.join(5_000)
        assertEquals(listOf("create", "process start 42", "process end 42", "destroy 42"), native.events.toList())
    }

    @Test
    fun closeTwiceDestroysOnceAndDenoiseAfterCloseFails() {
        val native = FakeNative().also { it.release.countDown() }
        val rnnoise = Rnnoise(native)
        rnnoise.close()
        rnnoise.close()
        assertEquals(listOf("create", "destroy 42"), native.events.toList())
        assertFailsWith<IllegalStateException> { rnnoise.denoise(FloatArray(RNNOISE_FRAME_SAMPLES)) }
    }

    @Test
    fun denoiseRejectsWrongFrameSize() {
        val rnnoise = Rnnoise(FakeNative().also { it.release.countDown() })
        assertFailsWith<IllegalArgumentException> { rnnoise.denoise(FloatArray(100)) }
    }
}
