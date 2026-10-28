package org.example.app.media

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
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
 * Records about one second on a real device or emulator with the AudioRecord + MediaCodec Opus path and validates
 * the file with the rules a receiver applies. Runs in the emulator CI job (task G3) on API 29 and 36.
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

    /**
     * Some platforms (the API 29 AOSP emulator) have no Opus encoder. Until task "E5b: bundled Opus fallback encoder"
     * lands, the recorder must fail safe with Failed(Encoder) there. When E5b lands, delete this branch so the
     * no-encoder case runs the full test below instead.
     */
    private fun assertFailsSafeWithoutEncoder(recorder: VoiceRecorder) {
        val started = recorder.start()
        val failed = assertIs<StartResult.Failed>(started, "start() returned $started; ${diagnostics()}")
        assertEquals(VoiceFailure.Encoder, failed.reason, diagnostics())
        assertEquals(emptyList(), dir.list()?.toList() ?: emptyList())
    }

    @Test fun oneSecondRecordingIsValidOggOpus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = VoiceRecorder(context, dir)
        if (!platformHasOpusEncoder()) return assertFailsSafeWithoutEncoder(recorder)
        val started = recorder.start()
        assertIs<StartResult.Started>(started, "start() returned ${(started as? StartResult.Failed)?.reason ?: started}; ${diagnostics()}")
        assertTrue(recorder.isRecording)
        Thread.sleep(1_200)
        val result = recorder.stop()
        val ok = assertIs<StopResult.Success>(result, "stop() returned $result")
        assertTrue(ok.recording.durationSeconds in 0.7..2.0, "duration ${ok.recording.durationSeconds}")
        val stream = VoiceNoteValidator.validate(ok.recording.file.readBytes())
        assertEquals(1, stream.channels)
        assertTrue(stream.packets.isNotEmpty())
        assertEquals(16_000L, stream.inputSampleRate)
        assertEquals(312, stream.preSkip, "standard Opus pre-skip in 48 kHz units")
        assertEquals(listOf(ok.recording.file.name), dir.list()!!.toList())
    }

    @Test fun immediateStopIsTooShortNotNull() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = VoiceRecorder(context, dir)
        if (!platformHasOpusEncoder()) return assertFailsSafeWithoutEncoder(recorder)
        val started = recorder.start()
        assertIs<StartResult.Started>(started, "start() returned ${(started as? StartResult.Failed)?.reason ?: started}; ${diagnostics()}")
        val result = recorder.stop()
        assertIs<StopResult.TooShort>(result, "stop() returned $result")
        assertEquals(emptyList(), dir.list()?.toList() ?: emptyList())
    }
}
