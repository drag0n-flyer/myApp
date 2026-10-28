package org.example.app.core.calls

import org.example.app.core.chats.Contact
import org.example.app.core.settings.CallSettings
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallEngineTest {
    private val server = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    private fun engine(network: NetworkType = NetworkType.Wifi, settings: CallSettings = CallSettings()) =
        SimulatedCallEngine(server, settings, network)

    @Test fun playsGoodWeakAudioOnlyReconnectingThenLost() {
        val e = engine()
        e.start("riya", video = true)
        assertEquals(CallPhase.Connecting, e.state.value!!.phase)
        e.advance(2.0)
        assertEquals(CallPhase.Active, e.state.value!!.phase)

        e.simulate(SimulatedNetwork.Good)
        assertNull(e.state.value!!.banner)
        assertEquals(CallQuality.Good, e.state.value!!.quality)

        e.simulate(SimulatedNetwork.Weak)
        assertEquals(CallBanner.VideoLowered, e.state.value!!.banner)
        assertEquals("call_banner_video_lowered", e.state.value!!.banner!!.textKey)
        assertEquals(CallQuality.Weak, e.state.value!!.quality)

        e.simulate(SimulatedNetwork.VeryWeak)
        assertEquals(CallBanner.AudioOnly, e.state.value!!.banner)
        assertEquals("call_banner_audio_only", e.state.value!!.banner!!.textKey)
        assertEquals(false, e.state.value!!.isVideoOn)

        e.simulate(SimulatedNetwork.Lost)
        assertEquals(CallPhase.Reconnecting, e.state.value!!.phase)
        assertEquals("call_banner_reconnecting", e.state.value!!.banner!!.textKey)

        e.advance(SimulatedCallEngine.RECONNECT_TIMEOUT)
        assertEquals(CallPhase.Ended(CallEndReason.ConnectionLost), e.state.value!!.phase)
        assertEquals("call_banner_connection_lost", e.state.value!!.banner!!.textKey)
    }

    @Test fun finishedCallIsFrozen() {
        val e = engine()
        e.start("riya", video = false)
        e.hangUp()
        assertEquals(CallPhase.Ended(CallEndReason.HungUp), e.state.value!!.phase)
        e.setNetwork(NetworkType.Mobile)
        assertEquals(NetworkType.Wifi, e.state.value!!.network)
        e.reset()
        assertNull(e.state.value)
    }

    @Test fun mobileDataKeepsVideoOffWhenSettingSaysSo() {
        val e = engine(NetworkType.Mobile)
        e.start("riya", video = true)
        assertEquals(false, e.state.value!!.isVideoOn)
        assertEquals(true, e.state.value!!.isVideoCall)
    }

    @Test fun elapsedAndDataUsedGrowWhileActive() {
        val e = engine()
        e.start("riya", video = false)
        e.advance(62.0)
        assertEquals(62L, e.state.value!!.elapsedSeconds)
        assertTrue(e.state.value!!.bytesUsed > 0)
        e.advance(3600.0)
        assertEquals(3662L, e.state.value!!.elapsedSeconds)
    }

    @Test fun incomingCanBeAnsweredOrDeclined() {
        val e = engine()
        e.simulateIncoming("riya", video = true)
        assertEquals(CallPhase.Incoming, e.state.value!!.phase)
        e.answer(video = true)
        assertEquals(CallPhase.Active, e.state.value!!.phase)
        assertEquals(true, e.state.value!!.isVideoOn)

        val d = engine()
        d.simulateIncoming("riya", video = false)
        d.decline()
        assertEquals(CallPhase.Ended(CallEndReason.Declined), d.state.value!!.phase)
    }

    @Test fun modePreferenceChangesEffectiveMode() {
        val e = engine()
        e.start("riya", video = false)
        e.setMode(ModePreference.Fixed(DataMode.UltraLow))
        assertEquals(DataMode.UltraLow, e.state.value!!.effectiveMode)
    }

    @Test fun controlsToggle() {
        val e = engine()
        e.start("riya", video = true)
        e.setMuted(true); e.setSpeaker(false); e.setVideo(false)
        val s = e.state.value!!
        assertTrue(s.isMuted); assertEquals(false, s.isSpeakerOn); assertEquals(false, s.isVideoOn)
    }
}
