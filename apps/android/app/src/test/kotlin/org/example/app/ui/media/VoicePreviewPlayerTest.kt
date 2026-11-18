package org.example.app.ui.media

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoicePreviewPlayerTest {
    private class FakeEngine : VoiceEngine {
        var listener: VoiceEngine.Listener? = null
        var started = 0
        var paused = 0
        var released = 0
        override var positionMs = 0
        override fun prepare(file: File, listener: VoiceEngine.Listener) { this.listener = listener }
        override fun start() { started++ }
        override fun pause() { paused++ }
        override fun release() { released++ }
    }

    private val engines = mutableListOf<FakeEngine>()
    private fun player() = VoicePreviewPlayer(File("/x/note.ogg")) { FakeEngine().also { engines += it } }

    @Test fun theFirstTapPreparesThenPlaysWhenReady() {
        val p = player()
        p.toggle()
        assertFalse(p.playing)
        engines[0].listener!!.onPrepared(7000)
        assertTrue(p.playing)
        assertEquals(7000, p.durationMs)
        assertEquals(1, engines[0].started)
    }

    @Test fun tappingAgainPausesAndAgainContinues() {
        val p = player()
        p.toggle(); engines[0].listener!!.onPrepared(7000)
        p.toggle()
        assertFalse(p.playing)
        assertEquals(1, engines[0].paused)
        p.toggle()
        assertTrue(p.playing)
        assertEquals(2, engines[0].started)
    }

    @Test fun pollingFollowsThePosition() {
        val p = player()
        p.toggle(); engines[0].listener!!.onPrepared(7000)
        engines[0].positionMs = 3200
        p.poll()
        assertEquals(3200, p.positionMs)
    }

    @Test fun finishingRewindsAndAnotherTapPlaysFromTheStart() {
        val p = player()
        p.toggle(); engines[0].listener!!.onPrepared(7000)
        engines[0].listener!!.onCompleted()
        assertFalse(p.playing)
        assertEquals(0, p.positionMs)
        p.toggle(); engines[1].listener!!.onPrepared(7000)
        assertTrue(p.playing)
    }

    @Test fun aFailureStopsQuietly() {
        val p = player()
        p.toggle(); engines[0].listener!!.onError()
        assertFalse(p.playing)
        assertEquals(1, engines[0].released)
    }

    @Test fun releaseFreesTheEngine() {
        val p = player()
        p.toggle(); engines[0].listener!!.onPrepared(7000)
        p.release()
        assertEquals(1, engines[0].released)
        assertFalse(p.playing)
    }
}
