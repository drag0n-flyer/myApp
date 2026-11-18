package org.example.app.ui.media

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private class FakeEngine : VoiceEngine {
    var listener: VoiceEngine.Listener? = null
    var started = false
    var paused = false
    var released = false
    var preparedFile: File? = null
    override var positionMs = 0

    override fun prepare(file: File, listener: VoiceEngine.Listener) { preparedFile = file; this.listener = listener }
    override fun start() { started = true; paused = false }
    override fun pause() { paused = true }
    override fun release() { released = true }
}

/** Counts how often playback took and gave back the phone's audio focus. */
private class FakeFocus : VoiceFocus {
    var granted = true
    var held = false
    var requests = 0
    var abandons = 0
    var onLost: (() -> Unit)? = null
    override fun request(onLost: () -> Unit): Boolean {
        requests++
        if (!granted) return false
        held = true
        this.onLost = onLost
        return true
    }
    override fun abandon() { abandons++; held = false }
}

/** Opens a file per message id; a message can be held back until [gates] is completed. */
private class GatedOpener : AttachmentOpener {
    val gates = mutableMapOf<String, CompletableDeferred<Boolean>>()
    val opened = mutableListOf<Closing>()
    val missing = mutableSetOf<String>()

    class Closing(val id: String) : OpenedMedia {
        var closed = false
        override val file = File("/plain/$id")
        override fun close() { closed = true }
    }

    override suspend fun open(messageId: String): OpenedMedia? {
        gates[messageId]?.await()
        if (messageId in missing) return null
        return Closing(messageId).also { opened += it }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoicePlayerTest {
    private val engines = mutableListOf<FakeEngine>()
    private val opener = GatedOpener()
    private val focus = FakeFocus()

    private fun TestScope.player(o: AttachmentOpener? = opener) =
        VoicePlayer(backgroundScope, o, { FakeEngine().also { engines += it } }, StandardTestDispatcher(testScheduler), tickMs = 100, focus = focus)

    private fun TestScope.playing(p: VoicePlayer, id: String, durationMs: Int = 8_000): FakeEngine {
        p.toggle(id)
        runCurrent()
        val engine = engines.last()
        engine.listener!!.onPrepared(durationMs)
        return engine
    }

    @Test fun aTapOpensThePlaintextAndPlaysAfterItIsPrepared() = runTest {
        val p = player()
        p.toggle("a")
        runCurrent()
        assertEquals(File("/plain/a"), engines.single().preparedFile)
        // Still loading: the bubble is already this message's, but nothing plays yet.
        assertEquals(VoiceState(messageId = "a"), p.state.value)
        engines.single().listener!!.onPrepared(8_000)
        assertEquals(VoiceState(messageId = "a", playing = true, durationMs = 8_000), p.state.value)
        assertTrue(engines.single().started)
    }

    @Test fun aSecondTapPausesAndAThirdResumes() = runTest {
        val p = player()
        val engine = playing(p, "a")
        p.toggle("a")
        assertFalse(p.state.value.playing)
        assertEquals("a", p.state.value.messageId)
        assertTrue(engine.paused)
        p.toggle("a")
        assertTrue(p.state.value.playing)
        assertFalse(engine.released)
    }

    @Test fun onlyOneVoiceMessagePlaysAtATime() = runTest {
        val p = player()
        val first = playing(p, "a")
        val second = playing(p, "b")
        assertTrue(first.released)
        assertTrue(opener.opened.single { it.id == "a" }.closed)
        assertEquals("b", p.state.value.messageId)
        assertTrue(p.state.value.playing)
        assertFalse(second.released)
        assertFalse(opener.opened.single { it.id == "b" }.closed)
    }

    @Test fun anOpenThatFinishesAfterAnotherTapIsClosedAndNeverPlayed() = runTest {
        val p = player()
        opener.gates["a"] = CompletableDeferred()
        p.toggle("a")
        runCurrent()
        p.toggle("b")
        runCurrent()
        opener.gates.getValue("a").complete(true)
        runCurrent()
        // "a" arrived late: its plaintext is deleted and no player was made for it.
        assertTrue(opener.opened.single { it.id == "a" }.closed)
        assertEquals(1, engines.size)
        assertEquals("b", p.state.value.messageId)
    }

    @Test fun aTapWhileLoadingCancelsTheLoad() = runTest {
        val p = player()
        opener.gates["a"] = CompletableDeferred()
        p.toggle("a")
        runCurrent()
        p.toggle("a")
        assertEquals(VoiceState(), p.state.value)
        opener.gates.getValue("a").complete(true)
        runCurrent()
        assertTrue(opener.opened.single().closed)
        assertTrue(engines.isEmpty())
    }

    @Test fun aMessageThatCannotBeOpenedFailsAndATapTriesAgain() = runTest {
        val p = player()
        opener.missing += "a"
        p.toggle("a")
        runCurrent()
        assertEquals(VoiceState(failedId = "a"), p.state.value)
        opener.missing.clear()
        playing(p, "a")
        assertNull(p.state.value.failedId)
        assertTrue(p.state.value.playing)
    }

    @Test fun noOpenerFailsWithoutCrashing() = runTest {
        val p = player(o = null)
        p.toggle("a")
        runCurrent()
        assertEquals("a", p.state.value.failedId)
    }

    @Test fun aPlayerErrorFailsTheMessageAndFreesEverything() = runTest {
        val p = player()
        val engine = playing(p, "a")
        engine.listener!!.onError()
        assertEquals(VoiceState(failedId = "a"), p.state.value)
        assertTrue(engine.released)
        assertTrue(opener.opened.single().closed)
    }

    @Test fun anErrorWhilePreparingFailsToo() = runTest {
        val p = player()
        p.toggle("a")
        runCurrent()
        engines.single().listener!!.onError()
        assertEquals("a", p.state.value.failedId)
        assertTrue(engines.single().released)
    }

    @Test fun reachingTheEndStopsAndFreesEverything() = runTest {
        val p = player()
        val engine = playing(p, "a")
        engine.listener!!.onCompleted()
        assertEquals(VoiceState(), p.state.value)
        assertTrue(engine.released)
        assertTrue(opener.opened.single().closed)
    }

    @Test fun progressFollowsThePlayer() = runTest {
        val p = player()
        val engine = playing(p, "a")
        engine.positionMs = 2_500
        advanceTimeBy(150)
        runCurrent()
        assertEquals(2_500, p.state.value.positionMs)
        engine.positionMs = 4_000
        advanceTimeBy(100)
        runCurrent()
        assertEquals(4_000, p.state.value.positionMs)
    }

    @Test fun pausingStopsTheProgressUpdates() = runTest {
        val p = player()
        val engine = playing(p, "a")
        p.toggle("a")
        engine.positionMs = 3_000
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0, p.state.value.positionMs)
    }

    @Test fun stoppingFreesEverythingAndShowsNothingPlaying() = runTest {
        val p = player()
        val engine = playing(p, "a")
        p.stop()
        assertEquals(VoiceState(), p.state.value)
        assertTrue(engine.released)
        assertTrue(opener.opened.single().closed)
        // A late callback of the stopped player changes nothing.
        engine.listener!!.onPrepared(1_000)
        engine.listener!!.onCompleted()
        assertEquals(VoiceState(), p.state.value)
    }

    @Test fun releasedForGoodWhenTheScreenLeaves() = runTest {
        val p = player()
        val engine = playing(p, "a")
        p.release()
        assertTrue(engine.released)
        assertTrue(opener.opened.single().closed)
        p.toggle("b")
        runCurrent()
        advanceUntilIdle()
        assertEquals(VoiceState(), p.state.value)
        assertEquals(1, engines.size)
    }

    @Test fun playingTakesTheAudioFocusAndEveryWayOfStoppingGivesItBack() = runTest {
        val p = player()
        val engine = playing(p, "a")
        assertTrue(focus.held)
        assertEquals(1, focus.requests)
        // Pause gives it back, resume takes it again.
        p.toggle("a")
        assertFalse(focus.held)
        p.toggle("a")
        assertTrue(focus.held)
        assertEquals(2, focus.requests)
        // The end of the message.
        engine.listener!!.onCompleted()
        assertFalse(focus.held)
        // Stop, and the screen leaving.
        playing(p, "b")
        assertTrue(focus.held)
        p.stop()
        assertFalse(focus.held)
        playing(p, "c")
        p.release()
        assertFalse(focus.held)
    }

    @Test fun aFailedMessageDoesNotKeepTheFocus() = runTest {
        val p = player()
        val engine = playing(p, "a")
        engine.listener!!.onError()
        assertFalse(focus.held)
    }

    @Test fun losingTheFocusToACallOrAnotherAppPauses() = runTest {
        val p = player()
        val engine = playing(p, "a")
        focus.onLost!!.invoke()
        assertFalse(p.state.value.playing)
        assertEquals("a", p.state.value.messageId)
        assertTrue(engine.paused)
        assertFalse(focus.held)
    }

    @Test fun nothingPlaysWhenTheFocusIsRefused() = runTest {
        focus.granted = false
        val p = player()
        p.toggle("a")
        runCurrent()
        engines.single().listener!!.onPrepared(8_000)
        assertFalse(p.state.value.playing)
        assertFalse(engines.single().started)
    }

    @Test fun clockShowsMinutesAndSeconds() {
        assertEquals("0:00", voiceClock(0))
        assertEquals("0:07", voiceClock(7_400))
        assertEquals("1:05", voiceClock(65_000))
        assertEquals("30:00", voiceClock(1_800_000))
        assertEquals("0:00", voiceClock(-5))
    }
}
