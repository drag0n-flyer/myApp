package org.example.app.webrtc

import org.webrtc.OpusVoiceEncoder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The bundled voice-note encoder of the project's WebRTC AAR (tools/webrtc patch 0008). */
class OpusVoiceEncoderTest {
    /** One 20 ms frame of a 440 Hz tone, 16-bit little-endian. */
    private fun toneFrame(): ByteArray {
        val out = ByteArray(OpusVoiceEncoder.FRAME_BYTES)
        for (i in 0 until OpusVoiceEncoder.FRAME_SAMPLES) {
            val s = (8_000 * sin(2 * PI * 440 * i / OpusVoiceEncoder.SAMPLE_RATE)).toInt()
            out[2 * i] = s.toByte()
            out[2 * i + 1] = (s shr 8).toByte()
        }
        return out
    }

    @Test fun formatIsTheVoiceNoteFormat() {
        assertEquals(16_000, OpusVoiceEncoder.SAMPLE_RATE)
        assertEquals(1, OpusVoiceEncoder.CHANNELS)
        assertEquals(16_000, OpusVoiceEncoder.BIT_RATE)
        assertEquals(640, OpusVoiceEncoder.FRAME_BYTES)
    }

    @Test fun preSkipIs312() {
        OpusVoiceEncoder().use { assertEquals(312, it.preSkip()) }
    }

    @Test fun encodesOneSecondNearSixteenKbps() {
        OpusVoiceEncoder().use { encoder ->
            val frame = toneFrame()
            val packets = List(50) { encoder.encode(frame, 0) }
            assertTrue(packets.all { it.isNotEmpty() })
            // TOC byte (RFC 6716 section 3.1): bit 2 is the stereo flag.
            val toc = packets.last()[0].toInt() and 0xff
            assertEquals(0, toc and 0x04, "mono")
            val bytes = packets.sumOf { it.size }
            assertTrue(bytes in 1_000..3_000, "one second is $bytes bytes, 16 kbps is 2000")
        }
    }

    @Test fun shortInputAndClosedEncoderThrow() {
        val encoder = OpusVoiceEncoder()
        assertFailsWith<IllegalArgumentException> { encoder.encode(ByteArray(10), 0) }
        assertFailsWith<IllegalArgumentException> { encoder.encode(ByteArray(OpusVoiceEncoder.FRAME_BYTES), 1) }
        encoder.close()
        encoder.close()
        assertFailsWith<IllegalStateException> { encoder.encode(toneFrame(), 0) }
    }
}
