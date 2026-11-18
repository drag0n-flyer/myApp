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
import org.example.app.core.chats.VoiceGesture
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
        override var capturedSeconds = 3.0
        override var level = 0f
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
        assertEquals(4.5, (c.state.value as CaptureState.Recording).seconds)
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

    // ---- hold to record, slide to cancel, slide up to lock, preview (VoiceGesture drives the capture) ----

    private fun recordingOf(phase: VoiceGesture.State, seconds: Double = 0.0) = CaptureState.Recording(seconds, phase = phase)

    @Test fun holdingStartsTheRecorderOnlyAfter150Ms() = runTest {
        val c = capture()
        c.press(rtl = false)
        advanceTimeBy(149)
        runCurrent()
        assertEquals(0, recorder.starts)
        assertEquals(CaptureState.Idle, c.state.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, recorder.starts)
        assertEquals(recordingOf(VoiceGesture.State.recording), c.state.value)
    }

    @Test fun aTapShowsTheHintForTwoSecondsAndNeverStartsTheRecorder() = runTest {
        val c = capture()
        c.press(rtl = false)
        c.lift()
        runCurrent()
        assertEquals(0, recorder.starts)
        assertEquals(true, c.hint.value)
        advanceTimeBy(1_900)
        runCurrent()
        assertEquals(true, c.hint.value)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(false, c.hint.value)
    }

    private fun TestScope.hold(c: VoiceCapture) {
        c.press(rtl = false)
        advanceTimeBy(200)
        runCurrent()
    }

    @Test fun releasingBeforeOneSecondThrowsTheFileAwayAndSendsNothing() = runTest {
        val c = capture()
        hold(c)
        recorder.capturedSeconds = 0.9
        c.lift()
        runCurrent()
        assertEquals(1, recorder.cancels, "the recorder deletes its file on cancel")
        assertEquals(0, recorder.stops)
        assertTrue(notes.isEmpty())
        assertEquals(CaptureState.Idle, c.state.value)
        assertEquals(true, c.hint.value)
    }

    @Test fun releasingAtOneSecondSends() = runTest {
        val c = capture()
        hold(c)
        recorder.capturedSeconds = 1.0
        c.lift()
        runCurrent()
        assertEquals(1, notes.size)
        assertEquals(0, recorder.cancels)
        assertEquals(CaptureState.Idle, c.state.value)
    }

    @Test fun slidingPastTheCancelDistanceAndReleasingCancels() = runTest {
        val c = capture()
        hold(c)
        recorder.capturedSeconds = 5.0
        c.drag(-100f, 0f)
        assertEquals(recordingOf(VoiceGesture.State.willCancel), (c.state.value as CaptureState.Recording).copy(seconds = 0.0))
        c.lift()
        runCurrent()
        assertEquals(1, recorder.cancels)
        assertTrue(notes.isEmpty())
        assertEquals(CaptureState.Idle, c.state.value)
        assertEquals(false, c.hint.value, "a deliberate cancel shows no hint")
    }

    @Test fun slidingPastTheCancelDistanceAndBackSendsLikeANormalRelease() = runTest {
        val c = capture()
        hold(c)
        recorder.capturedSeconds = 5.0
        c.drag(-100f, 0f)
        c.drag(-99f, 0f)
        c.lift()
        runCurrent()
        assertEquals(1, notes.size)
        assertEquals(0, recorder.cancels)
    }

    @Test fun slidingUpLocksAndTheFingerCanLift() = runTest {
        val c = capture()
        hold(c)
        c.drag(0f, -70f)
        c.lift()
        runCurrent()
        assertEquals(VoiceGesture.State.locked, (c.state.value as CaptureState.Recording).phase)
        assertEquals(0, recorder.stops)
        assertEquals(0, recorder.cancels)
        assertTrue(notes.isEmpty())
    }

    @Test fun stoppingALockedRecordingShowsAPreviewThatSendsOrDeletes() = runTest {
        val file = File.createTempFile("note", ".ogg")
        recorder.stopResult = StopResult.Success(Recording(file, 7.0))
        val c = capture()
        hold(c)
        c.drag(0f, -80f); c.lift(); runCurrent()
        c.stop()
        runCurrent()
        assertEquals(CaptureState.Preview(file, 7.0), c.state.value)
        assertTrue(notes.isEmpty())
        c.send()
        runCurrent()
        assertEquals(listOf(file), notes)
        assertEquals(CaptureState.Idle, c.state.value)
        file.delete()
    }

    @Test fun deletingAPreviewDeletesTheFile() = runTest {
        val file = File.createTempFile("note", ".ogg")
        recorder.stopResult = StopResult.Success(Recording(file, 7.0))
        val c = capture()
        c.activate()
        runCurrent()
        c.stop()
        runCurrent()
        c.delete()
        runCurrent()
        val end = System.currentTimeMillis() + 5_000
        while (file.exists() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(false, file.exists())
        assertTrue(notes.isEmpty())
        assertEquals(CaptureState.Idle, c.state.value)
    }

    @Test fun deletingALockedRecordingCancelsTheRecorder() = runTest {
        val c = capture()
        c.activate()
        runCurrent()
        c.delete()
        runCurrent()
        assertEquals(1, recorder.cancels)
        assertEquals(CaptureState.Idle, c.state.value)
    }

    @Test fun activatingStartsALockedRecording() = runTest {
        val c = capture()
        c.activate()
        runCurrent()
        assertEquals(1, recorder.starts)
        assertEquals(recordingOf(VoiceGesture.State.locked), c.state.value)
    }

    @Test fun theLimitTurnsAnyRecordingIntoAPreviewAndNeverSends() = runTest {
        val file = File.createTempFile("note", ".ogg")
        recorder.stopResult = StopResult.Success(Recording(file, 1800.0))
        val c = capture()
        hold(c)
        c.limitReached()
        runCurrent()
        assertEquals(CaptureState.Preview(file, 1800.0), c.state.value)
        assertTrue(notes.isEmpty())
        file.delete()
    }

    @Test fun anInterruptionKeepsTheRecordingAsAPreview() = runTest {
        val file = File.createTempFile("note", ".ogg")
        recorder.stopResult = StopResult.Success(Recording(file, 4.0))
        val c = capture()
        c.activate()
        runCurrent()
        c.interrupted()
        runCurrent()
        assertEquals(CaptureState.Preview(file, 4.0), c.state.value)
        assertTrue(notes.isEmpty())
        file.delete()
    }

    @Test fun anInterruptionOfAnEmptyRecordingLeavesNothing() = runTest {
        recorder.stopResult = StopResult.TooShort
        val c = capture()
        c.activate()
        runCurrent()
        c.interrupted()
        runCurrent()
        assertEquals(CaptureState.Idle, c.state.value)
    }

    @Test fun releasingWhileTheMicrophoneIsStillOpeningDropsTheRecording() = runTest {
        val c = capture()
        c.press(rtl = false)
        advanceTimeBy(160)   // the start is queued on the io dispatcher but has not run
        recorder.capturedSeconds = 0.0   // the microphone has delivered nothing yet
        c.lift()
        runCurrent()
        val end = System.currentTimeMillis() + 5_000
        while (recorder.cancels == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(1, recorder.cancels)
        assertEquals(CaptureState.Idle, c.state.value)
        assertTrue(notes.isEmpty())
    }

    @Test fun theLevelMeterScrollsAndKeepsThirtyBars() = runTest {
        val c = capture()
        c.activate()
        runCurrent()
        for (i in 1..40) {
            recorder.level = i / 100f
            advanceTimeBy(200)
            runCurrent()
        }
        val levels = (c.state.value as CaptureState.Recording).levels
        assertEquals(30, levels.size)
        assertEquals(0.40f, levels.last())
        assertEquals(0.11f, levels.first())
    }

    @Test fun aHoldThatIsInterruptedIsKeptAsAPreviewAndNeverSent() = runTest {
        val file = File.createTempFile("note", ".ogg")
        recorder.stopResult = StopResult.Success(Recording(file, 3.0))
        val c = capture()
        hold(c)
        c.interrupted()
        runCurrent()
        assertEquals(CaptureState.Preview(file, 3.0), c.state.value)
        assertTrue(notes.isEmpty())
        file.delete()
    }

    @Test fun aHoldInterruptedUnderOneSecondIsDiscarded() = runTest {
        val c = capture()
        hold(c)
        recorder.capturedSeconds = 0.9
        c.interrupted()
        runCurrent()
        assertEquals(CaptureState.Idle, c.state.value)
        assertEquals(1, recorder.cancels)
        assertEquals(0, recorder.stops)
        assertTrue(notes.isEmpty())
    }

    // ---- lead's rulings: minimum on Send, keep on leaving ----

    @Test fun sendFromTheLockedBarUnderOneSecondIsRefusedWithTheHint() = runTest {
        val c = capture()
        c.activate()
        runCurrent()
        recorder.capturedSeconds = 0.9
        c.send()
        runCurrent()
        assertTrue(notes.isEmpty())
        assertEquals(1, recorder.cancels)
        assertEquals(CaptureState.Idle, c.state.value)
        assertEquals(true, c.hint.value)
    }

    @Test fun sendFromAPreviewUnderOneSecondIsRefusedAndTheFileIsDeleted() = runTest {
        val file = File.createTempFile("note", ".ogg")
        recorder.stopResult = StopResult.Success(Recording(file, 0.9))
        val c = capture()
        c.activate()
        runCurrent()
        c.stop()
        runCurrent()
        c.send()
        runCurrent()
        val end = System.currentTimeMillis() + 5_000
        while (file.exists() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(false, file.exists())
        assertTrue(notes.isEmpty())
        assertEquals(true, c.hint.value)
    }

    @Test fun leavingWhileRecordingStopsAndKeepsThePreview() = runTest {
        val file = File.createTempFile("note", ".ogg")
        recorder.stopResult = StopResult.Success(Recording(file, 5.0))
        val kept = java.util.concurrent.LinkedBlockingQueue<CaptureState.Preview>()
        val c = VoiceCapture(backgroundScope, recorder, { notes += it }, { notices += it }, StandardTestDispatcher(testScheduler), keep = { kept.add(it) })
        c.activate()
        runCurrent()
        c.release()
        val p = kept.poll(5, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(CaptureState.Preview(file, 5.0), p)
        assertTrue(file.exists())
        assertEquals(0, recorder.cancels)
        file.delete()
    }

    @Test fun aKeptPreviewIsShownAgainByTheNextCapture() = runTest {
        val file = File.createTempFile("note", ".ogg")
        val c = VoiceCapture(backgroundScope, recorder, { notes += it }, { notices += it }, StandardTestDispatcher(testScheduler), initial = CaptureState.Preview(file, 5.0))
        assertEquals(CaptureState.Preview(file, 5.0), c.state.value)
        c.send()
        runCurrent()
        assertEquals(listOf(file), notes)
        file.delete()
    }

    @Test fun theKeepBoxHandsAPreviewToTheNextScreenOfTheSameChatOnly() {
        val file = File("a.ogg")
        val got = mutableListOf<CaptureState.Preview>()
        VoiceKeep.put("chat1", CaptureState.Preview(file, 3.0))
        VoiceKeep.take("other") { got += it }
        assertTrue(got.isEmpty())
        VoiceKeep.take("chat1") { got += it }
        assertEquals(listOf(CaptureState.Preview(file, 3.0)), got)
        // Taken once only.
        VoiceKeep.take("chat1") { got += it }
        assertEquals(1, got.size)
    }

    private fun TestScope.screenScope(job: kotlinx.coroutines.Job) =
        kotlinx.coroutines.CoroutineScope(StandardTestDispatcher(testScheduler) + job)

    @Test fun aSendInFlightWhenTheScreenLeavesKeepsTheNoteAsAPreview() = runTest {
        val file = File.createTempFile("note", ".ogg")
        recorder.stopResult = StopResult.Success(Recording(file, 3.0))
        val kept = mutableListOf<CaptureState.Preview>()
        val job = kotlinx.coroutines.Job()
        val c = VoiceCapture(screenScope(job), recorder, { notes += it }, { notices += it }, StandardTestDispatcher(testScheduler), keep = { kept += it })
        c.activate()
        runCurrent()
        c.send()          // finish(thenSend = true) is queued
        c.release()       // the screen leaves ...
        job.cancel()      // ... and Compose cancels its scope before the queued work ran
        runCurrent()
        assertEquals(listOf(CaptureState.Preview(file, 3.0)), kept)
        assertTrue(notes.isEmpty())
        assertTrue(file.exists())
        file.delete()
    }

    @Test fun aPreviewSendQueuedWhenTheScreenLeavesStaysAPreview() = runTest {
        val file = File.createTempFile("note", ".ogg")
        val kept = mutableListOf<CaptureState.Preview>()
        val job = kotlinx.coroutines.Job()
        val c = VoiceCapture(screenScope(job), recorder, { notes += it }, { notices += it }, StandardTestDispatcher(testScheduler), keep = { kept += it }, initial = CaptureState.Preview(file, 5.0))
        c.send()
        c.release()
        job.cancel()
        runCurrent()
        assertEquals(listOf(CaptureState.Preview(file, 5.0)), kept)
        assertTrue(notes.isEmpty())
        assertTrue(file.exists())
        file.delete()
    }

    /** The leave sequence of rememberVoiceControls: [forget] is what stops the leaving screen from receiving its own preview. */
    private fun TestScope.leaveAndReturn(forget: Boolean): CaptureState.Preview? {
        VoiceKeep.clear()
        val file = File.createTempFile("note", ".ogg")
        recorder.stopResult = StopResult.Success(Recording(file, 6.0))
        val job = kotlinx.coroutines.Job()
        val madeIn = VoiceKeep.currentGeneration()
        val first = VoiceCapture(screenScope(job), recorder, { notes += it }, { notices += it }, StandardTestDispatcher(testScheduler), keep = { VoiceKeep.put("acct/chat", it, madeIn) })
        val waiter: (CaptureState.Preview) -> Unit = { first.adopt(it) }
        VoiceKeep.take("acct/chat", waiter)
        first.activate()
        runCurrent()
        if (forget) VoiceKeep.forget("acct/chat", waiter)
        first.release()          // onDispose
        job.cancel()             // Compose cancels the screen's scope right after
        runCurrent()
        val got = java.util.concurrent.LinkedBlockingQueue<CaptureState.Preview>()
        // The next screen of the same chat asks; the stopped recording is kept (or arrives a moment later).
        VoiceKeep.take("acct/chat") { got.add(it) }
        return got.poll(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test fun aLeavingScreenDoesNotReceiveItsOwnKeptPreview() = runTest {
        val p = leaveAndReturn(forget = true)
        assertTrue(p != null && p.seconds == 6.0, "the next screen gets the preview")
        p!!.file.delete()
        VoiceKeep.clear()
    }

    @Test fun withoutForgetTheLeavingScreenSwallowsThePreview() = runTest {
        // Documents the bug the forget call prevents.
        val p = leaveAndReturn(forget = false)
        assertEquals(null, p)
        VoiceKeep.clear()
    }

    @Test fun aKeepThatFinishesAfterTheWipeDeletesItsFile() = runTest {
        val file = File.createTempFile("note", ".ogg")
        val release = java.util.concurrent.CountDownLatch(1)
        val slow = object : RecorderPort {
            override fun start() = StartResult.Started
            override fun stop(): StopResult { release.await(5, java.util.concurrent.TimeUnit.SECONDS); return StopResult.Success(Recording(file, 5.0)) }
            override fun cancel() = Unit
            override val capturedSeconds = 5.0
        }
        val madeIn = VoiceKeep.currentGeneration()
        val c = VoiceCapture(backgroundScope, slow, { notes += it }, { notices += it }, StandardTestDispatcher(testScheduler), keep = { VoiceKeep.put("acct/wipe", it, madeIn) })
        c.activate()
        runCurrent()
        c.release()            // keepInBackground is now blocked in stop()
        VoiceKeep.clear()      // sign-out or the local-data wipe
        release.countDown()
        val end = System.currentTimeMillis() + 5_000
        while (file.exists() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(false, file.exists())
        val got = mutableListOf<CaptureState.Preview>()
        VoiceKeep.take("acct/wipe") { got += it }
        assertTrue(got.isEmpty())
        VoiceKeep.clear()
    }

    @Test fun clearingDeletesKeptFilesAndDropsWaiters() {
        val file = File.createTempFile("note", ".ogg")
        VoiceKeep.put("acct/a", CaptureState.Preview(file, 3.0))
        val got = mutableListOf<CaptureState.Preview>()
        VoiceKeep.take("acct/waiter") { got += it }
        VoiceKeep.clear()
        assertEquals(false, file.exists())
        val late = File.createTempFile("note", ".ogg")
        VoiceKeep.put("acct/waiter", CaptureState.Preview(late, 3.0))
        assertTrue(got.isEmpty(), "the waiter was dropped")
        VoiceKeep.take("acct/waiter") { got += it }
        assertEquals(listOf(CaptureState.Preview(late, 3.0)), got)
        VoiceKeep.clear()
    }

    @Test fun aPreviewKeptByOneAccountIsNotHandedToAnotherOnTheSameChatId() {
        val file = File("c.ogg")
        VoiceKeep.put("alice/peer", CaptureState.Preview(file, 3.0))
        val got = mutableListOf<CaptureState.Preview>()
        VoiceKeep.take("bob/peer") { got += it }
        assertTrue(got.isEmpty())
        VoiceKeep.clear()
    }

    @Test fun aForgottenWaiterIsNotCalledBack() {
        val got = mutableListOf<CaptureState.Preview>()
        val waiter: (CaptureState.Preview) -> Unit = { got += it }
        VoiceKeep.take("acct/f", waiter)
        VoiceKeep.forget("acct/f", waiter)
        VoiceKeep.put("acct/f", CaptureState.Preview(File("d.ogg"), 3.0))
        assertTrue(got.isEmpty())
        VoiceKeep.clear()
    }

    @Test fun theKeepBoxCallsBackWhenThePreviewArrivesLate() {
        val got = mutableListOf<CaptureState.Preview>()
        VoiceKeep.take("chat2") { got += it }
        VoiceKeep.put("chat2", CaptureState.Preview(File("b.ogg"), 2.0))
        assertEquals(1, got.size)
    }
}
