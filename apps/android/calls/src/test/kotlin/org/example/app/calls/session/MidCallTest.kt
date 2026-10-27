package org.example.app.calls.session

import org.example.app.calls.policy.IceState
import org.example.app.calls.policy.MediaStats
import org.example.app.contracts.CallSealException
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.CallSignalPayload.Kind
import org.example.app.contracts.DataMode
import org.example.app.core.calls.CallBanner
import org.example.app.core.calls.CallEndReason
import org.example.app.core.calls.CallPhase
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.CallSummary
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.example.app.core.calls.DataMode as CoreMode

private const val CALLEE_FP = "sha-256 11:22:33"
private const val CALLER_FP = "sha-256 AB:CD:EF"
private fun peerOffer(f: String, sdpFP: String? = null) =
    CallSignalPayload(Kind.renegotiate, "c1", sdp = sdp(sdpFP ?: f), dtlsFingerprint = f)
private fun List<CallEffect>.hasMakeOffer(restart: Boolean) =
    any { if (restart) it is CallEffect.RestartIce else it is CallEffect.CreateOffer }

/** Caller, ICE connected. Peer (callee) device "d1"; pinned fingerprint is the callee's. */
internal fun callerActive(video: Boolean = false, clock: ManualClock = ManualClock()): CallMachine {
    val m = CallMachine(ctx(), PlainSealer(), clock)
    m.handle(CallEvent.Start(bob, video, "c1"))
    m.handle(CallEvent.Server(CallServerEvent.Answered("c1", "d1")))
    m.handle(CallEvent.Remote(CallSignalPayload(Kind.offer, "c1", sdp = sdp(CALLEE_FP), dtlsFingerprint = CALLEE_FP), "d1"))
    m.handle(CallEvent.LocalAnswer(sdp(CALLER_FP)))
    m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Connected)))
    return m
}

/** Callee, ICE connected. Peer (caller) device "dA"; pinned fingerprint is the caller's. */
private fun calleeActive(clock: ManualClock = ManualClock()): CallMachine {
    val m = CallMachine(ctx(), PlainSealer(), clock)
    m.handle(CallEvent.Incoming("c1", bob, "dA", CallSignalPayload(Kind.ring, "c1", video = false)))
    m.handle(CallEvent.LocalOffer(sdp(CALLEE_FP)))
    m.handle(CallEvent.Answer(false))
    m.handle(CallEvent.Remote(CallSignalPayload(Kind.answer, "c1", sdp = sdp(CALLER_FP), dtlsFingerprint = CALLER_FP), "dA"))
    m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Connected)))
    return m
}

/** Mirrors the iPhone's MidCallTests, plus the Android brief's mid-call cases. */
class MidCallTest {
    @Test fun remoteFromWrongSenderIsRejected() {
        val m = machine()
        m.handle(CallEvent.Start(bob, false, "c1"))
        m.handle(CallEvent.Server(CallServerEvent.Answered("c1", "d1")))
        val fx = m.handle(CallEvent.RemoteRejected(CallSealException.WrongSender, "d1"))
        assertEquals(CallPhase.Ended(CallEndReason.Failed), m.state?.phase)
        assertTrue(hangupFrame in fx && CallEffect.End(CallEndReason.Failed) in fx)
        assertFalse(fx.hasSetRemote())
    }

    @Test fun fingerprintMismatchEndsCall() {
        val m = machine()
        m.handle(CallEvent.Start(bob, false, "c1"))
        m.handle(CallEvent.Server(CallServerEvent.Answered("c1", "d1")))
        val fx = m.handle(CallEvent.Remote(CallSignalPayload(Kind.offer, "c1", sdp = sdp("sha-256 99"), dtlsFingerprint = CALLEE_FP), "d1"))
        assertEquals(CallPhase.Ended(CallEndReason.Failed), m.state?.phase)
        assertFalse(fx.hasAcceptOffer())
    }

    @Test fun statsDriveAdaptor() {
        val m = callerActive(video = true)
        val fx = m.handle(CallEvent.Stats(MediaStats(availableOutgoingBitrate = 55_000.0)))
        assertTrue(CallEffect.SetVideo(null, DataMode.High) in fx && CallEffect.SetAudioMaxBitrate(16_000) in fx)
        assertEquals(CallBanner.AudioOnly, m.state?.banner)
        assertEquals(CoreMode.DataSaver, m.state?.effectiveMode)
        val pub = fx.published()
        assertEquals(CallBanner.AudioOnly, pub?.banner)
        assertEquals(CoreMode.DataSaver, pub?.effectiveMode)
    }

    @Test fun modeChangeRenegotiates() {
        val m = callerActive()
        val step = m.handle(CallEvent.SetPreference(ModePreference.Fixed(CoreMode.Balanced)))
        assertTrue(CallEffect.SetAudioMaxBitrate(24_000) in step)
        assertFalse(step.hasMakeOffer(restart = false))
        val fx = m.handle(CallEvent.Stats(MediaStats(availableOutgoingBitrate = 40_000.0)))
        assertEquals(CoreMode.DataSaver, m.state?.effectiveMode)
        assertTrue(CallEffect.CreateOffer(profile(DataMode.Balanced)) in fx)
        val re = m.handle(CallEvent.LocalOffer(sdp(CALLER_FP))).signal(Kind.renegotiate)
        assertEquals(sdp(CALLER_FP), re?.sdp)
        assertEquals(CALLER_FP, re?.dtlsFingerprint)
        val ans = CallSignalPayload(Kind.renegotiateAnswer, "c1", sdp = sdp(CALLEE_FP), dtlsFingerprint = CALLEE_FP)
        assertTrue(CallEffect.AcceptAnswer(sdp(CALLEE_FP), profile(DataMode.DataSaver)) in m.handle(CallEvent.Remote(ans, "d1")))
        assertFalse(m.handle(CallEvent.Stats(MediaStats(availableOutgoingBitrate = 40_000.0))).hasMakeOffer(restart = false))
    }

    @Test fun calleeAnswersModeChangeRenegotiate() {
        val callee = calleeActive()
        assertTrue(callee.handle(CallEvent.Remote(peerOffer(CALLER_FP), "dA")).hasAcceptOffer())
        assertEquals(CALLEE_FP, callee.handle(CallEvent.LocalAnswer(sdp(CALLEE_FP))).signal(Kind.renegotiateAnswer)?.dtlsFingerprint)
    }

    @Test fun calleeStartsModeChangeRenegotiation() {
        val m = calleeActive()
        assertTrue(CallEffect.CreateOffer(profile(DataMode.DataSaver)) in m.handle(CallEvent.SetPreference(ModePreference.Fixed(CoreMode.DataSaver))))
        assertEquals(sdp(CALLEE_FP), m.handle(CallEvent.LocalOffer(sdp(CALLEE_FP))).signal(Kind.renegotiate)?.sdp)
    }

    @Test fun inCallPreferenceSignalsCeiling() {
        val m = callerActive()
        val fx = m.handle(CallEvent.SetPreference(ModePreference.Fixed(CoreMode.UltraLow)))
        assertTrue(signalFrame(CallSignalPayload(Kind.modeChange, "c1", ceiling = DataMode.UltraLow)) in fx)
        assertEquals(ModePreference.Fixed(CoreMode.UltraLow), m.state?.inCallPreference)
    }

    @Test fun peerCeilingCaps() {
        val m = callerActive()
        val mc = CallSignalPayload(Kind.modeChange, "c1", ceiling = DataMode.DataSaver)
        assertTrue(m.handle(CallEvent.Remote(mc, "d2")).isEmpty())
        val fx = m.handle(CallEvent.Remote(mc, "d1"))
        assertTrue(m.state!!.effectiveMode <= CoreMode.DataSaver && CallEffect.SetAudioMaxBitrate(16_000) in fx)
        m.handle(CallEvent.Stats(MediaStats(availableOutgoingBitrate = 5_000_000.0)))
        assertTrue(m.state!!.effectiveMode <= CoreMode.DataSaver)
    }

    // Renegotiation rules

    @Test fun glareCallerWinsOnCallee() {
        val m = calleeActive()
        m.handle(CallEvent.SetPreference(ModePreference.Fixed(CoreMode.DataSaver)))
        m.handle(CallEvent.LocalOffer(sdp(CALLEE_FP)))
        val fx = m.handle(CallEvent.Remote(peerOffer(CALLER_FP), "dA"))
        assertEquals(CallEffect.Rollback, fx.first())
        assertTrue(fx[1] is CallEffect.AcceptOffer)
        assertNotNull(m.handle(CallEvent.LocalAnswer(sdp(CALLEE_FP))).signal(Kind.renegotiateAnswer))
    }

    /** Brief: both sides send `renegotiate` at once; the caller's offer wins and the callee rolls back. */
    @Test fun glareCallerWins() {
        val callerClock = ManualClock()
        val caller = callerActive(clock = callerClock)
        val callee = calleeActive()
        // Both lower their setting at once: each makes an offer.
        caller.handle(CallEvent.SetPreference(ModePreference.Fixed(CoreMode.DataSaver)))
        callee.handle(CallEvent.SetPreference(ModePreference.Fixed(CoreMode.DataSaver)))
        val callerOffer = caller.handle(CallEvent.LocalOffer(sdp(CALLER_FP))).signal(Kind.renegotiate) ?: fail("caller offer")
        val calleeOffer = callee.handle(CallEvent.LocalOffer(sdp(CALLEE_FP))).signal(Kind.renegotiate) ?: fail("callee offer")
        // The caller ignores the callee's offer; the callee rolls its own back and takes the caller's.
        assertTrue(caller.handle(CallEvent.Remote(calleeOffer, "d1")).isEmpty())
        val atCallee = callee.handle(CallEvent.Remote(callerOffer, "dA"))
        assertEquals(listOf(CallEffect.Rollback), atCallee.take(1))
        assertTrue(atCallee[1] is CallEffect.AcceptOffer)
        val answer = callee.handle(CallEvent.LocalAnswer(sdp(CALLEE_FP))).signal(Kind.renegotiateAnswer) ?: fail("answer")
        assertTrue(caller.handle(CallEvent.Remote(answer, "d1")).hasAcceptAnswer())
        assertFalse(caller.state!!.isEnded || callee.state!!.isEnded)
    }

    @Test fun callerAcceptsCalleeModeChangeOffer() {
        val m = callerActive()
        val fx = m.handle(CallEvent.Remote(peerOffer(CALLEE_FP), "d1"))
        assertTrue(fx.hasAcceptOffer() && CallEffect.Rollback !in fx)
        assertEquals(CALLER_FP, m.handle(CallEvent.LocalAnswer(sdp(CALLER_FP))).signal(Kind.renegotiateAnswer)?.dtlsFingerprint)
    }

    @Test fun callerChecksCalleeOffer() {
        val a = callerActive()
        assertTrue(a.handle(CallEvent.Remote(peerOffer(CALLEE_FP), "d2")).isEmpty())
        assertFalse(a.state!!.isEnded)
        val b = callerActive()
        val bad = b.handle(CallEvent.Remote(peerOffer(CALLEE_FP, sdpFP = "sha-256 99"), "d1"))
        assertEquals(CallPhase.Ended(CallEndReason.Failed), b.state?.phase)
        assertTrue(hangupFrame in bad)
        val c = callerActive()
        val unpinned = c.handle(CallEvent.Remote(peerOffer("sha-256 77"), "d1"))
        assertEquals(CallPhase.Ended(CallEndReason.Failed), c.state?.phase)
        assertFalse(unpinned.hasAcceptOffer())
    }

    /** Brief: a `renegotiate` or `renegotiateAnswer` must carry the fingerprint the call started with. */
    @Test fun renegotiateKeepsFingerprint() {
        // Same key, different case and spacing: still the pinned fingerprint.
        val ok = callerActive()
        assertTrue(ok.handle(CallEvent.Remote(peerOffer("sha-256 11:22:33", sdpFP = "SHA-256  11:22:33"), "d1")).hasSetRemote())
        // A new, self-consistent key mid-call: the call ends, the SDP is never applied.
        val swapped = callerActive()
        val fx = swapped.handle(CallEvent.Remote(peerOffer("sha-256 44:55:66"), "d1"))
        assertFalse(fx.hasSetRemote())
        assertEquals(CallPhase.Ended(CallEndReason.Failed), swapped.state?.phase)
        assertTrue(hangupFrame in fx)
        // The same rule holds for the callee answering with a renegotiateAnswer.
        val callee = calleeActive()
        callee.handle(CallEvent.SetPreference(ModePreference.Fixed(CoreMode.DataSaver)))
        callee.handle(CallEvent.LocalOffer(sdp(CALLEE_FP)))
        val swappedAnswer = CallSignalPayload(Kind.renegotiateAnswer, "c1", sdp = sdp("sha-256 44:55:66"), dtlsFingerprint = "sha-256 44:55:66")
        assertFalse(callee.handle(CallEvent.Remote(swappedAnswer, "dA")).hasSetRemote())
        assertEquals(CallPhase.Ended(CallEndReason.Failed), callee.state?.phase)
        // A renegotiate without a claimed fingerprint fails too.
        val missing = callerActive()
        assertFalse(missing.handle(CallEvent.Remote(CallSignalPayload(Kind.renegotiate, "c1", sdp = sdp(CALLEE_FP)), "d1")).hasSetRemote())
        assertEquals(CallPhase.Ended(CallEndReason.Failed), missing.state?.phase)
    }

    @Test fun callerOneRenegotiationAtATime() {
        val m = callerActive()
        assertTrue(m.handle(CallEvent.Remote(peerOffer(CALLEE_FP), "d1")).hasAcceptOffer())
        assertTrue(m.handle(CallEvent.Remote(peerOffer(CALLEE_FP), "d1")).isEmpty())
        m.handle(CallEvent.LocalAnswer(sdp(CALLER_FP)))
        assertTrue(m.handle(CallEvent.Remote(peerOffer(CALLEE_FP), "d1")).hasAcceptOffer())
    }

    @Test fun callerOfferWinsGlareAgainstCalleeOffer() {
        val clock = ManualClock()
        val m = callerActive(clock = clock)
        val req = CallSignalPayload(Kind.renegotiate, "c1")
        assertTrue(m.handle(CallEvent.Remote(req, "d1")).hasMakeOffer(restart = true))
        m.handle(CallEvent.LocalOffer(sdp(CALLER_FP)))
        clock.advance(1.0)
        assertTrue(m.handle(CallEvent.Remote(peerOffer(CALLEE_FP), "d1")).isEmpty())
        clock.advance(10.0)
        val fx = m.handle(CallEvent.Remote(peerOffer(CALLEE_FP), "d1"))
        assertEquals(CallEffect.Rollback, fx.first())
        assertTrue(fx[1] is CallEffect.AcceptOffer)
    }

    @Test fun requestWithoutSDPIsOnlyARestartRequest() {
        val callee = calleeActive()
        assertTrue(callee.handle(CallEvent.Remote(CallSignalPayload(Kind.renegotiate, "c1"), "dA")).isEmpty())
        assertFalse(callee.state!!.isEnded)
    }

    @Test fun callerDoesNotRestartWhileOwingAnAnswer() {
        val c2 = ManualClock()
        val n = callerActive(clock = c2)
        assertTrue(n.handle(CallEvent.Remote(peerOffer(CALLEE_FP), "d1")).hasAcceptOffer())
        c2.advance(1.0)
        assertTrue(n.handle(CallEvent.Remote(CallSignalPayload(Kind.renegotiate, "c1"), "d1")).isEmpty())
        n.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Disconnected)))
        c2.advance(2.5)
        assertFalse(n.handle(CallEvent.Tick).hasMakeOffer(restart = true))
        n.handle(CallEvent.LocalAnswer(sdp(CALLER_FP)))
        c2.advance(5.0)
        assertTrue(n.handle(CallEvent.Tick).hasMakeOffer(restart = true))
    }

    @Test fun callerIgnoresRenegotiateBeforeFirstAnswerIsSent() {
        val m = machine()
        m.handle(CallEvent.Start(bob, false, "c1"))
        m.handle(CallEvent.Server(CallServerEvent.Answered("c1", "d1")))
        m.handle(CallEvent.Remote(CallSignalPayload(Kind.offer, "c1", sdp = sdp(CALLEE_FP), dtlsFingerprint = CALLEE_FP), "d1"))
        assertTrue(m.handle(CallEvent.Remote(peerOffer(CALLEE_FP), "d1")).isEmpty())
        assertNotNull(m.handle(CallEvent.LocalAnswer(sdp(CALLER_FP))).signal(Kind.answer))
    }

    @Test fun bothSidesLowerCeilingAtOnceAndConverge() {
        val caller = callerActive()
        val callee = calleeActive()
        val c1 = caller.handle(CallEvent.SetPreference(ModePreference.Fixed(CoreMode.DataSaver)))
        val c2 = callee.handle(CallEvent.SetPreference(ModePreference.Fixed(CoreMode.DataSaver)))
        assertTrue(c1.hasMakeOffer(restart = false) && c2.hasMakeOffer(restart = false))
        val callerOffer = caller.handle(CallEvent.LocalOffer(sdp(CALLER_FP))).signal(Kind.renegotiate) ?: fail("caller offer")
        val calleeOffer = callee.handle(CallEvent.LocalOffer(sdp(CALLEE_FP))).signal(Kind.renegotiate) ?: fail("callee offer")
        val atCallee = callee.handle(CallEvent.Remote(callerOffer, "dA"))
        val atCaller = caller.handle(CallEvent.Remote(calleeOffer, "d1"))
        assertEquals(CallEffect.Rollback, atCallee.first())
        assertTrue(atCallee[1] is CallEffect.AcceptOffer)
        assertTrue(atCaller.isEmpty())
        val answer = callee.handle(CallEvent.LocalAnswer(sdp(CALLEE_FP))).signal(Kind.renegotiateAnswer) ?: fail("no answer")
        assertTrue(CallEffect.AcceptAnswer(sdp(CALLEE_FP), profile(DataMode.DataSaver)) in caller.handle(CallEvent.Remote(answer, "d1")))
        repeat(3) { i ->
            assertFalse(caller.handle(CallEvent.Stats(MediaStats(availableOutgoingBitrate = 5_000_000.0))).hasMakeOffer(false), "caller $i")
            assertFalse(callee.handle(CallEvent.Stats(MediaStats(availableOutgoingBitrate = 5_000_000.0))).hasMakeOffer(false), "callee $i")
        }
        assertFalse(caller.state!!.isEnded || callee.state!!.isEnded)
    }

    // Reconnect, end

    @Test fun reconnectFlow() {
        val clock = ManualClock()
        val m = callerActive(clock = clock)
        clock.advance(5.0)
        m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Disconnected)))
        assertEquals(CallPhase.Reconnecting, m.state?.phase)
        assertEquals(CallBanner.Reconnecting, m.state?.banner)
        clock.advance(2.4)
        assertFalse(m.handle(CallEvent.Tick).hasMakeOffer(restart = true))
        clock.advance(0.1)
        assertTrue(m.handle(CallEvent.Tick).hasMakeOffer(restart = true))
        clock.advance(27.5)
        val fx = m.handle(CallEvent.Tick)
        assertEquals(CallPhase.Ended(CallEndReason.ConnectionLost), m.state?.phase)
        assertTrue(hangupFrame in fx && CallEffect.RecordHistory(CallSummary.Audio(35.0), outgoing = true) in fx)
    }

    @Test fun pathChangeRestartsIce() {
        val m = callerActive()
        assertTrue(m.handle(CallEvent.PathChanged).hasMakeOffer(restart = true))
        assertEquals(null, m.state?.banner)
    }

    @Test fun hangupByUser() {
        val clock = ManualClock()
        val m = callerActive(video = true, clock = clock)
        clock.advance(12.0)
        val fx = m.handle(CallEvent.Hangup)
        assertTrue(hangupFrame in fx && CallEffect.RecordHistory(CallSummary.Video(12.0), outgoing = true) in fx)
        assertEquals(CallPhase.Ended(CallEndReason.HungUp), m.state?.phase)
    }

    @Test fun endedCallIgnoresLateEvents() {
        val m = callerActive()
        m.handle(CallEvent.Hangup)
        val frozen = m.state
        val inputs = listOf(
            CallEvent.Answer(true), CallEvent.Decline, CallEvent.Hangup, CallEvent.SetPreference(ModePreference.Fixed(CoreMode.High)),
            CallEvent.SetVideo(true), CallEvent.SetMuted(true), CallEvent.SetSpeaker(true),
            CallEvent.Server(CallServerEvent.Answered("c1", "d1")), CallEvent.Server(CallServerEvent.Ended("c1", "missed")),
            CallEvent.Status("c1", CallStatus.Ended("missed")),
            CallEvent.Remote(peerOffer(CALLEE_FP), "d1"), CallEvent.RemoteRejected(CallSealException.Undecryptable, "d1"),
            CallEvent.Media(MediaEvent.IceStateChanged(IceState.Disconnected)), CallEvent.Media(MediaEvent.LocalCandidate(cand(1))),
            CallEvent.LocalOffer(sdp(CALLER_FP)), CallEvent.LocalAnswer(sdp(CALLER_FP)),
            CallEvent.Stats(MediaStats(availableOutgoingBitrate = 1.0)), CallEvent.PathChanged, CallEvent.Tick, CallEvent.LocalFailure,
            CallEvent.ContextChanged(ctx(network = NetworkType.Mobile)),
        )
        for (i in inputs) assertTrue(m.handle(i).isEmpty(), "$i")
        assertEquals(frozen, m.state)
    }

    // Random sequences

    @Test fun exactlyOneHistoryEntry() {
        val devices = listOf("d1", "d2", "dA")
        val fps = listOf(CALLEE_FP, CALLER_FP, "sha-256 99")
        val reasons = listOf("declined", "missed", "hangup", "answered_elsewhere", "failed", "weird")
        val kinds = Kind.entries
        val modes = DataMode.entries
        for (seed in 1..6) {
            val rng = Random(seed)
            val clock = ManualClock()
            val m = CallMachine(ctx(), PlainSealer(), clock)
            var historyCount = 0
            var handledEnd = true
            repeat(500) {
                val wasLive = m.state?.isEnded == false
                val input: CallEvent = when (rng.nextInt(26)) {
                    0 -> CallEvent.Start(bob, rng.nextBoolean(), "c1")
                    1 -> CallEvent.Incoming("c1", bob, devices.random(rng), CallSignalPayload(Kind.ring, "c1", video = rng.nextBoolean(), ceiling = modes.random(rng)))
                    2 -> CallEvent.Answer(rng.nextBoolean())
                    3 -> CallEvent.Decline
                    4 -> CallEvent.Hangup
                    5 -> CallEvent.SetPreference(if (rng.nextBoolean()) ModePreference.Auto else ModePreference.Fixed(CoreMode.entries.random(rng)))
                    6 -> CallEvent.SetVideo(rng.nextBoolean())
                    7 -> CallEvent.Server(CallServerEvent.Answered("c1", devices.random(rng)))
                    8 -> CallEvent.Server(CallServerEvent.Ended("c1", reasons.random(rng)))
                    9, 10, 11 -> {
                        val f = fps.random(rng)
                        val p = CallSignalPayload(
                            kinds.random(rng), "c1",
                            sdp = if (rng.nextBoolean()) sdp(if (rng.nextBoolean()) f else fps[0]) else null,
                            dtlsFingerprint = if (rng.nextBoolean()) f else null,
                            candidate = if (rng.nextBoolean()) cand(rng.nextInt(100)) else null,
                            ceiling = modes.random(rng),
                        )
                        CallEvent.Remote(p, devices.random(rng))
                    }
                    12 -> CallEvent.RemoteRejected(CallSealException.Undecryptable, devices.random(rng))
                    13, 14 -> CallEvent.Media(MediaEvent.IceStateChanged(listOf(IceState.Connected, IceState.Disconnected, IceState.Failed, IceState.Checking, IceState.Closed).random(rng)))
                    15 -> CallEvent.Media(MediaEvent.LocalCandidate(cand(rng.nextInt(100))))
                    16 -> CallEvent.LocalOffer(sdp(fps.random(rng)))
                    17 -> CallEvent.LocalAnswer(sdp(fps.random(rng)))
                    18, 19 -> CallEvent.Stats(MediaStats(
                        availableOutgoingBitrate = if (rng.nextBoolean()) null else rng.nextDouble(0.0, 600_000.0),
                        inboundLossFraction = rng.nextDouble(0.0, 0.3),
                    ))
                    20 -> CallEvent.PathChanged
                    21 -> CallEvent.ContextChanged(ctx(network = listOf(NetworkType.Wifi, NetworkType.Mobile).random(rng)))
                    22 -> CallEvent.LocalFailure
                    23 -> CallEvent.Status("c1", listOf(CallStatus.Ringing, CallStatus.Answered, CallStatus.Unknown, CallStatus.Ended(reasons.random(rng))).random(rng))
                    else -> {
                        clock.advance(rng.nextDouble(0.0, 12.0))
                        CallEvent.Tick
                    }
                }
                val fx = m.handle(input)
                val isNowLive = m.state?.isEnded == false
                if (!wasLive && isNowLive) { historyCount = 0; handledEnd = false }
                historyCount += fx.count { it is CallEffect.RecordHistory }
                assertTrue(historyCount <= 1, "seed $seed: more than one history entry")
                val phase = m.state?.phase
                if (phase is CallPhase.Ended && !handledEnd) {
                    handledEnd = true
                    assertEquals(if (phase.reason == CallEndReason.AnsweredElsewhere) 0 else 1, historyCount, "seed $seed: ended ${phase.reason}")
                    assertEquals(1, fx.count { it is CallEffect.End }, "seed $seed: one End")
                }
            }
        }
    }
}
