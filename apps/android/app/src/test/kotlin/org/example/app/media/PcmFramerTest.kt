package org.example.app.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmFramerTest {
    private val frames = ArrayList<ByteArray>()
    private val framer = PcmFramer(frameBytes = 4)

    @Test fun cutsReadsOfAnySizeIntoWholeFrames() {
        framer.push(byteArrayOf(1, 2, 3, 4, 5, 6), 6) { frames += it }
        framer.push(byteArrayOf(7, 8, 9, 99), 3) { frames += it }
        assertEquals(2, frames.size)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), frames[0])
        assertContentEquals(byteArrayOf(5, 6, 7, 8), frames[1])
    }

    @Test fun flushPadsTheLastPartialFrameWithSilence() {
        framer.push(byteArrayOf(1, 2, 3, 4, 5), 5) { frames += it }
        framer.flush { frames += it }
        assertEquals(2, frames.size)
        assertContentEquals(byteArrayOf(5, 0, 0, 0), frames[1])
    }

    @Test fun flushWithoutLeftoverEmitsNothing() {
        framer.push(byteArrayOf(1, 2, 3, 4), 4) { frames += it }
        framer.flush { frames += it }
        framer.flush { frames += it }
        assertEquals(1, frames.size)
    }

    @Test fun emptyFramerFlushesNothing() {
        framer.flush { frames += it }
        assertTrue(frames.isEmpty())
    }
}
