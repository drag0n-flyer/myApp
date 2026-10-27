package org.example.app.media

import android.Manifest
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
 * the file with the rules a receiver applies. Runs on the emulator CI job.
 */
@RunWith(AndroidJUnit4::class)
class VoiceRecorderInstrumentedTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    private val dir = File.createTempFile("voice-it", "").also { it.delete() }

    @After fun cleanUp() { dir.deleteRecursively() }

    @Test fun oneSecondRecordingIsValidOggOpus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = VoiceRecorder(context, dir)
        assertIs<StartResult.Started>(recorder.start())
        assertTrue(recorder.isRecording)
        Thread.sleep(1_200)
        val result = recorder.stop()
        val ok = assertIs<StopResult.Success>(result, "stop() returned $result")
        assertTrue(ok.recording.durationSeconds in 0.7..2.0, "duration ${ok.recording.durationSeconds}")
        val stream = VoiceNoteValidator.validate(ok.recording.file.readBytes())
        assertEquals(1, stream.channels)
        assertTrue(stream.packets.isNotEmpty())
        assertEquals(16_000L, stream.inputSampleRate)
        assertEquals(listOf(ok.recording.file.name), dir.list()!!.toList())
    }

    @Test fun immediateStopIsTooShortNotNull() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = VoiceRecorder(context, dir)
        assertIs<StartResult.Started>(recorder.start())
        val result = recorder.stop()
        assertTrue(result is StopResult.TooShort || result is StopResult.Failed, "stop() returned $result")
        assertEquals(emptyList(), dir.list()?.toList() ?: emptyList())
    }
}
