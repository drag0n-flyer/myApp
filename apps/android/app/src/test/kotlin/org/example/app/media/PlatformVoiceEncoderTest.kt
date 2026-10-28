package org.example.app.media

import android.media.MediaCodec
import android.media.MediaFormat
import kotlin.test.Test
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
}
