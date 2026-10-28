package org.example.app.media

import android.media.MediaCodec
import android.media.MediaFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformVoiceEncoderTest {
    /** The phone has an Opus encoder but it will not configure or start: the reason is logged, then the bundled one is used. */
    @Test fun aPhoneEncoderThatFailsToStartLogsWhy() {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val name = runCatching { codec.name }.getOrNull() ?: MediaFormat.MIMETYPE_AUDIO_OPUS
        val logs = mutableListOf<String>()
        val encoder = PlatformVoiceEncoder.start(codec, log = { logs += it }) { throw IllegalStateException("configure refused") }
        assertNull(encoder)
        val line = logs.single()
        assertTrue(name in line, line)
        assertTrue("IllegalStateException" in line && "configure refused" in line, line)
    }

    private fun opusHead(preSkip: Int) = "OpusHead".toByteArray() + byteArrayOf(
        1, 1, (preSkip and 0xFF).toByte(), (preSkip shr 8).toByte(), 0x80.toByte(), 0x3E, 0, 0, 0, 0, 0,
    )

    private fun le64(v: Long) = ByteArray(8) { (v shr (8 * it)).toByte() }

    /** The phone encoder's config buffer is a plain `OpusHead` or Android's `AOPUSHDR`-wrapped one: both give its pre-skip. */
    @Test fun preSkipIsReadFromThePhoneEncodersOpusHead() {
        assertEquals(3_840, OpusHeadPreSkip.parse(opusHead(3_840)))
        val wrapped = "AOPUSHDR".toByteArray() + le64(19) + opusHead(312) +
            "AOPUSDLY".toByteArray() + le64(8) + le64(6_500_000) + "AOPUSPRL".toByteArray() + le64(8) + le64(80_000_000)
        assertEquals(312, OpusHeadPreSkip.parse(wrapped))
        assertNull(OpusHeadPreSkip.parse("OpusHead".toByteArray() + byteArrayOf(1, 1)), "cut short")
        assertNull(OpusHeadPreSkip.parse(ByteArray(40)), "no header")
    }

    /** A header pre-skip of 0 or over half a second (24000 at 48 kHz) is not believed: the note gets the standard 312. */
    @Test fun outOfRangePreSkipFallsBackTo312() {
        assertEquals(24_000, OpusHeadPreSkip.parse(opusHead(24_000)))
        assertNull(OpusHeadPreSkip.parse(opusHead(0)), "zero")
        assertNull(OpusHeadPreSkip.parse(opusHead(24_001)), "over half a second")
        assertNull(OpusHeadPreSkip.parse(opusHead(0xFFFF)), "max")
        assertEquals(VoiceNoteFinisher.DEFAULT_PRE_SKIP, PlatformVoiceEncoder.preSkipOrDefault(OpusHeadPreSkip.parse(opusHead(0))))
        assertEquals(3_840, PlatformVoiceEncoder.preSkipOrDefault(OpusHeadPreSkip.parse(opusHead(3_840))))
    }
}
