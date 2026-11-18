package org.example.app.ui.media

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.app.R
import org.example.app.media.Recording
import org.example.app.media.StartResult
import org.example.app.media.StopResult
import org.example.app.media.VoiceFailure

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCaptureTest {
    private class FakeRecorder : RecorderPort {
        var startResult: StartResult = StartResult.Started
        var stopResult: StopResult = StopResult.Success(Recording(File("/out/note.ogg"), 3.0))
        var starts = 0
        var stops = 0
        var cancels = 0
        override var capturedSeconds = 0.0
        override fun start(): StartResult { starts++; return startResult }
        override fun stop(): StopResult {
            stops++
            return stopResult
        }
        override fun cancel() { cancels++ }
    }

    private val recorder = FakeRecorder()
    private val notes = mutableListOf<File>()
    private val notices = mutableListOf<Int>()

    private var valid = true

    private fun TestScope.capture() =
        VoiceCapture(backgroundScope, recorder, { notes += it }, { notices += it }, StandardTestDispatcher(testScheduler), tickMs = 200, check = { valid })

    @Test fun beginningStartsTheRecorderAndShowsTheBar() = runTest {
        val c = capture()
        c.begin()
        runCurrent()
        assertEquals(1, recorder.starts)
        assertEquals(CaptureState.Recording(0.0), c.state.value)
    }

    @Test fun theElapsedTimeFollowsTheRecorder() = runTest {
        val c = capture()
        c.begin()
        runCurrent()
        recorder.capturedSeconds = 4.5
        advanceTimeBy(250)
        runCurrent()
        assertEquals(CaptureState.Recording(4.5), c.state.value)
    }

    @Test fun aSecondBeginWhileRecordingDoesNothing() = runTest {
        val c = capture()
        c.begin()
        runCurrent()
        c.begin()
        runCurrent()
        assertEquals(1, recorder.starts)
    }

    @Test fun sendingHandsOverTheNoteAndClosesTheBar() = runTest {
        val c = capture()
        c.begin()
        runCurrent()
        c.send()
        runCurrent()
        assertEquals(listOf(File("/out/note.ogg")), notes)
        assertEquals(CaptureState.Idle, c.state.value)
        assertTrue(notices.isEmpty())
    }

    @Test fun aNoteThatDoesNotPassTheCheckIsNotSentAndSaysSo() = runTest {
        val file = File.createTempFile("note", ".ogg")
        recorder.stopResult = StopResult.Success(Recording(file, 3.0))
        valid = false
        val c = capture()
        c.begin()
        runCurrent()
        c.send()
        runCurrent()
        assertEquals(emptyList(), notes)
        assertEquals(listOf(R.string.media_record_failed), notices)
        assertEquals(false, file.exists(), "the broken note is deleted")
        assertEquals(CaptureState.Idle, c.state.value)
    }

    @Test fun theButtonsWaitWhileTheNoteIsBeingFinished() = runTest {
        val c = capture()
        c.begin()
        runCurrent()
        recorder.capturedSeconds = 2.0
        c.send()
        // At once, before the note is finished: the bar shows it is busy, and a second press does nothing.
        assertEquals(CaptureState.Recording(2.0, stopping = true), c.state.value)
        c.send()
        c.cancel()
        runCurrent()
        assertEquals(1, recorder.stops)
        assertEquals(0, recorder.cancels)
        assertEquals(1, notes.size)
    }

    @Test fun cancellingThrowsTheRecordingAwayAndSendsNothing() = runTest {
        val c = capture()
        c.begin()
        runCurrent()
        c.cancel()
        runCurrent()
        assertEquals(1, recorder.cancels)
        assertEquals(CaptureState.Idle, c.state.value)
        assertTrue(notes.isEmpty())
    }

    @Test fun tooShortIsDroppedWithoutANote() = runTest {
        recorder.stopResult = StopResult.TooShort
        val c = capture()
        c.begin()
        runCurrent()
        c.send()
        runCurrent()
        assertTrue(notes.isEmpty())
        assertEquals(CaptureState.Idle, c.state.value)
    }

    @Test fun aFailedStartSaysWhyInPlainWords() = runTest {
        val expected = mapOf(
            VoiceFailure.Permission to R.string.media_mic_off,
            VoiceFailure.Microphone to R.string.media_mic_unavailable,
            VoiceFailure.Encoder to R.string.media_record_failed,
            VoiceFailure.Invalid to R.string.media_record_failed,
            VoiceFailure.Storage to R.string.media_record_failed,
        )
        for ((reason, text) in expected) {
            notices.clear()
            recorder.startResult = StartResult.Failed(reason)
            val c = capture()
            c.begin()
            runCurrent()
            assertEquals(listOf(text), notices, "reason=$reason")
            assertEquals(CaptureState.Idle, c.state.value)
        }
    }

    @Test fun aFailedFinishSaysWhyAndSendsNothing() = runTest {
        recorder.stopResult = StopResult.Failed(VoiceFailure.Storage)
        val c = capture()
        c.begin()
        runCurrent()
        c.send()
        runCurrent()
        assertEquals(listOf(R.string.media_record_failed), notices)
        assertTrue(notes.isEmpty())
        assertEquals(CaptureState.Idle, c.state.value)
    }

    @Test fun leavingTheScreenWhileRecordingCancelsTheRecording() = runTest {
        val c = capture()
        c.begin()
        runCurrent()
        c.release()
        // The cancel waits for the recording thread, so it must not run on the caller's (main) thread.
        val end = System.currentTimeMillis() + 5_000
        while (recorder.cancels == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(1, recorder.cancels)
        assertEquals(CaptureState.Idle, c.state.value)
        c.begin()
        runCurrent()
        assertEquals(1, recorder.starts)
    }

    @Test fun leavingWhileIdleTouchesNothing() = runTest {
        val c = capture()
        c.release()
        Thread.sleep(50)
        assertEquals(0, recorder.cancels)
    }
}
