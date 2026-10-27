package org.example.app.calls.session

import org.example.app.calls.policy.IceState
import org.example.app.calls.policy.MediaStats
import org.example.app.calls.policy.VideoTier
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.CallSignalPayload.Kind
import org.example.app.contracts.DataMode
import org.example.app.core.calls.ModePreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.core.calls.DataMode as CoreMode

private const val CALLEE_FP = "sha-256 11:22:33"
private fun good() = MediaStats(availableOutgoingBitrate = 900_000.0, roundTripTime = 0.05)
private fun offer(ceiling: DataMode?) =
    CallSignalPayload(Kind.offer, "c1", sdp = sdp(CALLEE_FP), dtlsFingerprint = CALLEE_FP, ceiling = ceiling)
private fun CallEffect.SetVideo.within(c: DataMode, max: VideoTier) = ceiling == c && (tier ?: VideoTier.P180) <= max

/** Mirrors the iPhone's VideoCeilingTests: the machine owns the ceiling, min(own setting, peer's). */
class VideoCeilingTest {
    /** Outgoing video call, answered with the callee's ceiling (null = not carried). */
    private fun caller(peerCeiling: DataMode?): CallMachine {
        val m = machine()
        m.handle(CallEvent.Start(bob, true, "c1"))
        m.handle(CallEvent.Server(CallServerEvent.Answered("c1", "d1")))
        m.handle(CallEvent.Remote(offer(peerCeiling), "d1"))
        m.handle(CallEvent.LocalAnswer(sdp("sha-256 AB:CD")))
        m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Connected)))
        return m
    }

    @Test fun callWithoutPeerCeilingIsClampedToOwnSetting() {
        val m = machine()
        m.handle(CallEvent.Start(bob, true, "c1"))
        m.handle(CallEvent.Server(CallServerEvent.Answered("c1", "d1")))
        val v = m.handle(CallEvent.SetPreference(ModePreference.Fixed(CoreMode.Balanced))).videoEffects()
        assertTrue(v.isNotEmpty())
        assertTrue(v.all { it.within(DataMode.Balanced, VideoTier.P270) })
        val all = (0 until 12).flatMap { m.handle(CallEvent.Stats(good())) }
        assertTrue(all.videoEffects().all { it.within(DataMode.Balanced, VideoTier.P270) })
    }

    @Test fun raisingTheSettingUnclampsToTheHigherTier() {
        val m = caller(peerCeiling = null)
        m.handle(CallEvent.SetPreference(ModePreference.Fixed(CoreMode.Balanced)))
        val raise = m.handle(CallEvent.SetPreference(ModePreference.Fixed(CoreMode.High))).videoEffects()
        assertTrue(raise.any { it.ceiling == DataMode.High })
        val all = (0 until 12).flatMap { m.handle(CallEvent.Stats(good())) }
        assertTrue(all.videoEffects().any { it.tier == VideoTier.P360 && it.ceiling == DataMode.High })
    }

    @Test fun peerCeilingArrivingLowerClampsImmediately() {
        val m = machine()
        m.handle(CallEvent.Start(bob, true, "c1"))
        m.handle(CallEvent.Server(CallServerEvent.Answered("c1", "d1")))
        val v = m.handle(CallEvent.Remote(offer(DataMode.Balanced), "d1")).videoEffects()
        assertTrue(v.any { it.within(DataMode.Balanced, VideoTier.P270) })
        val m2 = caller(peerCeiling = null)
        val fx2 = m2.handle(CallEvent.Remote(CallSignalPayload(Kind.modeChange, "c1", ceiling = DataMode.UltraLow), "d1"))
        assertTrue(fx2.videoEffects().all { it.ceiling == DataMode.UltraLow })
    }

    /** The initial offer (caller side) re-sends the running tier with the call's ceiling, even when nothing changed. */
    @Test fun initialOfferSendsTheCeiling() {
        val m = machine()
        m.handle(CallEvent.Start(bob, true, "c1"))
        m.handle(CallEvent.Server(CallServerEvent.Answered("c1", "d1")))
        assertEquals(listOf(CallEffect.SetVideo(VideoTier.P360, DataMode.High)), m.handle(CallEvent.Remote(offer(null), "d1")).videoEffects())
    }

    /** The callee's first answer from the caller sends the tier with the call's ceiling, peer's ceiling from the ring included. */
    @Test fun initialAnswerSendsTheCeiling() {
        val m = machine()
        m.handle(CallEvent.Incoming("c1", bob, "dA", CallSignalPayload(Kind.ring, "c1", video = true, ceiling = DataMode.Balanced)))
        m.handle(CallEvent.LocalOffer(sdp(CALLEE_FP)))
        m.handle(CallEvent.Answer(true))
        val ans = CallSignalPayload(Kind.answer, "c1", sdp = sdp("sha-256 AB:CD"), dtlsFingerprint = "sha-256 AB:CD")
        assertEquals(listOf(CallEffect.SetVideo(VideoTier.P270, DataMode.Balanced)), m.handle(CallEvent.Remote(ans, "dA")).videoEffects())
    }

    /** Every ceiling change re-sends a running tier, also when the tier itself stays the same. */
    @Test fun everyCeilingChangeResendsTheTier() {
        val m = caller(peerCeiling = DataMode.Balanced)   // running at p270 under balanced
        val fx = m.handle(CallEvent.SetPreference(ModePreference.Fixed(CoreMode.Balanced)))   // own drops high -> balanced; min unchanged
        assertTrue(fx.videoEffects().isEmpty())
        val up = m.handle(CallEvent.Remote(CallSignalPayload(Kind.modeChange, "c1", ceiling = DataMode.High), "d1"))
        assertTrue(up.videoEffects().isEmpty())   // min(own balanced, peer high) is still balanced
        val down = m.handle(CallEvent.Remote(CallSignalPayload(Kind.modeChange, "c1", ceiling = DataMode.DataSaver), "d1"))
        assertEquals(DataMode.DataSaver, down.videoEffects().single().ceiling)
        // p180 is the top tier at both data saver and ultra low: same tier, new ceiling, still re-sent.
        val lower = m.handle(CallEvent.Remote(CallSignalPayload(Kind.modeChange, "c1", ceiling = DataMode.UltraLow), "d1"))
        assertEquals(listOf(CallEffect.SetVideo(VideoTier.P180, DataMode.UltraLow)), lower.videoEffects())
    }
}
