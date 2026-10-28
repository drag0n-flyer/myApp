package org.example.app.media

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.example.app.messaging.media.ogg.VoiceNoteValidator
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records about one second on a real device or emulator and validates the file with the rules a receiver applies,
 * with the encoder the recorder picks (the platform's `audio/opus` MediaCodec, else the bundled libopus of task E5b)
 * and with the bundled one forced. Runs in the emulator CI job (task G3) on API 29 (no platform Opus encoder) and 36.
 */
@RunWith(AndroidJUnit4::class)
class VoiceRecorderInstrumentedTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    private val dir = File.createTempFile("voice-it", "").also { it.delete() }

    @After fun cleanUp() { dir.deleteRecursively() }

    /** Says why a start can fail on an emulator: the Opus encoders the platform lists and the AudioRecord state. */
    private fun diagnostics(): String {
        val encoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, true) } }
            .map { it.name }
        val minBuffer = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return "api=${Build.VERSION.SDK_INT} opusEncoders=$encoders minBuffer=$minBuffer"
    }

    private fun platformHasOpusEncoder(): Boolean = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        .any { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, true) } }

    @Test fun oneSecondRecordingIsValidOggOpus() {
        val recorder = VoiceRecorder(InstrumentationRegistry.getInstrumentation().targetContext, dir)
        recordOneSecondAndValidate(recorder)
        val expected = if (platformHasOpusEncoder()) VoiceEncoderKind.Platform else VoiceEncoderKind.Bundled
        assertEquals(expected, recorder.lastEncoder, diagnostics())
    }

    @Test fun bundledEncoderRecordingIsValidOggOpus() {
        val recorder = VoiceRecorder(InstrumentationRegistry.getInstrumentation().targetContext, dir)
        recorder.encoderChoice = VoiceEncoderChoice.BundledOnly
        recordOneSecondAndValidate(recorder)
        assertEquals(VoiceEncoderKind.Bundled, recorder.lastEncoder)
    }

    /**
     * Times the recording from the first captured frame, not from start(): the emulator's audio input starts
     * hundreds of milliseconds late, and a start-relative window measured 0.61 s after a 1.2 s sleep on API 36.
     */
    private fun recordOneSecondAndValidate(recorder: VoiceRecorder) {
        val startedAt = System.nanoTime()
        val started = recorder.start()
        assertIs<StartResult.Started>(started, "start() returned ${(started as? StartResult.Failed)?.reason ?: started}; ${diagnostics()}")
        assertTrue(recorder.isRecording)
        while (recorder.capturedSeconds == 0.0) {
            assertTrue(System.nanoTime() - startedAt < 3_000_000_000L, "no audio frame within 3 s; ${diagnostics()}")
            Thread.sleep(5)
        }
        val firstFrameMs = (System.nanoTime() - startedAt) / 1_000_000
        Log.i("VoiceRecorderIT", "first frame ${firstFrameMs} ms after start(), encoder ${recorder.lastEncoder}; ${diagnostics()}")
        Thread.sleep(1_200)
        val result = recorder.stop()
        val ok = assertIs<StopResult.Success>(result, "stop() returned $result")
        assertTrue(ok.recording.durationSeconds in 1.0..1.8, "duration ${ok.recording.durationSeconds}, first frame after $firstFrameMs ms")
        val stream = VoiceNoteValidator.validate(ok.recording.file.readBytes())
        assertEquals(1, stream.channels)
        assertTrue(stream.packets.isNotEmpty())
        assertEquals(16_000L, stream.inputSampleRate)
        // The bundled encoder writes the standard 312; a phone encoder writes the pre-skip its own header reported.
        if (recorder.lastEncoder == VoiceEncoderKind.Bundled) assertEquals(312, stream.preSkip, "standard Opus pre-skip in 48 kHz units")
        assertEquals(recorder.lastPreSkip, stream.preSkip, "the encoder's pre-skip, in 48 kHz units")
        assertEquals(listOf(ok.recording.file.name), dir.list()!!.toList())
    }

    @Test fun immediateStopIsTooShortNotNull() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = VoiceRecorder(context, dir)
        val started = recorder.start()
        assertIs<StartResult.Started>(started, "start() returned ${(started as? StartResult.Failed)?.reason ?: started}; ${diagnostics()}")
        val result = recorder.stop()
        assertIs<StopResult.TooShort>(result, "stop() returned $result")
        assertEquals(emptyList(), dir.list()?.toList() ?: emptyList())
    }
}
