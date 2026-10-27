package org.example.app.calls.session

import kotlinx.coroutines.test.runTest
import org.example.app.calls.policy.IceState
import org.example.app.calls.policy.IceTransportPolicy
import org.example.app.calls.policy.MediaStats
import org.example.app.contracts.CallSealException
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.CallSignalPayload.Kind
import org.example.app.contracts.DataMode
import org.example.app.contracts.IceCandidate
import org.example.app.core.calls.CallBanner
import org.example.app.core.calls.CallEndReason
import org.example.app.core.calls.CallPhase
import org.example.app.core.calls.CallQuality
import org.example.app.core.calls.DataModePolicy
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.CallSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.app.core.calls.DataMode as CoreMode

private const val FP = "sha-256 AB:CD:EF"
private fun outCtx(isContact: Boolean = true, network: NetworkType = NetworkType.Mobile) = ctx(isContact, network)
private fun offer(f: String = FP, ceiling: DataMode? = null, sdpFP: String = f) =
    CallSignalPayload(Kind.offer, "c1", sdp = sdp(sdpFP), dtlsFingerprint = f, ceiling = ceiling)
private val ownCeiling: DataMode =
    DataMode.valueOf(DataModePolicy.ceiling(server, outCtx().settings.call, NetworkType.Mobile, null).name)

/** Mirrors the iPhone's OutgoingCallTests, plus the Android brief's caller cases. */
class OutgoingCallTest {
    private class Started(val m: CallMachine, val clock: ManualClock, val fx: List<CallEffect>)

    private fun started(video: Boolean = false, context: CallContext = outCtx()): Started {
        val clock = ManualClock()
        val m = CallMachine(context, PlainSealer(), clock)
        val fx = m.handle(CallEvent.Start(bob, video, "c1"))
        return Started(m, clock, fx)
    }

    private fun CallMachine.answered(device: String = "d1") =
        handle(CallEvent.Server(CallServerEvent.Answered("c1", device)))

    private fun activeMachine(): Started {
        val s = started()
        s.m.answered()
        s.m.handle(RemoteSignal(offer(), "d1"))
        s.m.handle(CallEvent.LocalAnswer(sdp(FP)))
        s.m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Connected)))
        return s
    }

    private fun renegotiating(m: CallMachine, clock: ManualClock) {
        m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Disconnected)))
        clock.advance(2.5)
        m.handle(CallEvent.Tick)
        m.handle(CallEvent.LocalOffer(sdp(FP)))
    }

    private fun reAnswer(f: String, sdpFP: String? = null) =
        CallSignalPayload(Kind.renegotiateAnswer, "c1", sdp = sdp(sdpFP ?: f), dtlsFingerprint = f)

    @Test fun startEmitsInviteAndPrewarm() {
        val s = started()
        assertTrue(CallEffect.ConfigureMedia(IceTransportPolicy.All, video = false) in s.fx)
        assertTrue(
            CallEffect.SendFrame(CallFrame.INVITE, CallSignalPayload(Kind.ring, "c1", video = false, ceiling = ownCeiling)) in s.fx,
        )
        assertEquals(CallPhase.Ringing, s.fx.published()?.phase)
        assertEquals(CoreMode.valueOf(ownCeiling.name), s.m.state?.effectiveMode)
    }

    @Test fun strangerForcesRelay() {
        val s = started(context = outCtx(isContact = false))
        assertTrue(CallEffect.ConfigureMedia(IceTransportPolicy.Relay, video = false) in s.fx)
    }

    @Test fun answeredOfferIsAccepted() {
        val s = started()
        s.m.answered()
        val fx = s.m.handle(RemoteSignal(offer(), "d1"))
        val send = profile(DataMode.valueOf(s.m.state!!.effectiveMode.name))
        assertTrue(CallEffect.AcceptOffer(sdp(FP), send, profile(ownCeiling)) in fx)
        val fx2 = s.m.handle(CallEvent.LocalAnswer(sdp("sha-256 11:22")))
        assertTrue(
            signalFrame(
                CallSignalPayload(Kind.answer, "c1", sdp = sdp("sha-256 11:22"), dtlsFingerprint = "sha-256 11:22", ceiling = ownCeiling),
            ) in fx2,
        )
    }

    @Test fun fingerprintMismatchEndsCall() {
        val s = started()
        s.m.answered()
        val fx = s.m.handle(RemoteSignal(offer(f = "sha-256 00:00", sdpFP = FP), "d1"))
        assertEquals(CallPhase.Ended(CallEndReason.Failed), s.m.state?.phase)
        assertTrue(CallEffect.End(CallEndReason.Failed) in fx && hangupFrame in fx)
        assertFalse(fx.hasAcceptOffer())
    }

    /** Brief: no SetRemote, ends `failed`, sends `call.hangup`; the same payload again does nothing. */
    @Test fun fingerprintMismatchAbortsCall() {
        val s = started()
        s.m.answered()
        val forged = offer(f = FP, sdpFP = "sha-256 66:66:66")
        val fx = s.m.handle(RemoteSignal(forged, "d1"))
        assertFalse(fx.hasSetRemote())
        assertEquals(CallPhase.Ended(CallEndReason.Failed), s.m.state?.phase)
        assertTrue(hangupFrame in fx)
        assertTrue(s.m.handle(RemoteSignal(forged, "d1")).isEmpty())
        // An SDP with a second, different fingerprint line fails too: every line must match.
        val t = started()
        t.m.answered()
        val twoLines = "v=0\r\na=fingerprint:$FP\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=fingerprint:sha-256 66:66:66\r\n"
        val fx2 = t.m.handle(RemoteSignal(CallSignalPayload(Kind.offer, "c1", sdp = twoLines, dtlsFingerprint = FP), "d1"))
        assertFalse(fx2.hasSetRemote())
        assertEquals(CallPhase.Ended(CallEndReason.Failed), t.m.state?.phase)
    }

    @Test fun fingerprintCompareNormalisesCase() {
        val s = started()
        s.m.answered()
        // The SDP has lower-case hex and an upper-case hash name; the claim is the canonical form.
        val fx = s.m.handle(
            RemoteSignal(CallSignalPayload(Kind.offer, "c1", sdp = sdp("SHA-256 ab:cd:ef"), dtlsFingerprint = FP), "d1"),
        )
        assertTrue(fx.hasAcceptOffer())
        assertFalse(s.m.state!!.isEnded)
    }

    @Test fun offerWithoutFingerprintEndsCall() {
        val s = started()
        s.m.answered()
        val fx = s.m.handle(RemoteSignal(CallSignalPayload(Kind.offer, "c1", sdp = "v=0\r\n", dtlsFingerprint = FP), "d1"))
        assertFalse(fx.hasSetRemote())
        assertEquals(CallPhase.Ended(CallEndReason.Failed), s.m.state?.phase)
    }

    @Test fun peerCeilingLimitsSendMode() {
        val s = started()
        s.m.answered()
        val fx = s.m.handle(RemoteSignal(offer(ceiling = DataMode.DataSaver), "d1"))
        assertTrue(fx.any { it is CallEffect.AcceptOffer && it.send.mode == DataMode.DataSaver })
    }

    @Test fun iceConnectedGoesActive() {
        val s = activeMachine()
        assertEquals(CallPhase.Active, s.m.state?.phase)
    }

    @Test fun noAnswerEnds() {
        val s = started()
        val fx = s.m.handle(CallEvent.Server(CallServerEvent.Ended("c1", "missed")))
        assertEquals(CallPhase.Ended(CallEndReason.Missed), s.m.state?.phase)
        assertTrue(CallEffect.End(CallEndReason.Missed) in fx)
        assertTrue(CallEffect.RecordHistory(CallSummary.Audio(0.0), outgoing = true) in fx)
    }

    @Test fun declinedEnds() {
        val s = started()
        s.m.handle(CallEvent.Server(CallServerEvent.Ended("c1", "declined")))
        assertEquals(CallPhase.Ended(CallEndReason.Declined), s.m.state?.phase)
    }

    @Test fun endedForOtherCallIsIgnored() {
        val s = started()
        assertTrue(s.m.handle(CallEvent.Server(CallServerEvent.Ended("other", "missed"))).isEmpty())
        assertEquals(CallPhase.Ringing, s.m.state?.phase)
    }

    @Test fun callerTrickles() {
        val s = started()
        val c = cand(1)
        assertTrue(s.m.handle(CallEvent.Media(MediaEvent.LocalCandidate(c))).isEmpty())
        s.m.answered()
        s.m.handle(RemoteSignal(offer(), "d1"))
        val fx = s.m.handle(CallEvent.LocalAnswer(sdp(FP)))
        val candidateSignal = signalFrame(CallSignalPayload(Kind.candidate, "c1", candidate = c))
        assertTrue(candidateSignal in fx)
        val answerIdx = fx.indexOfFirst { it is CallEffect.SendFrame && it.payload?.kind == Kind.answer }
        assertEquals(fx.indexOf(candidateSignal) - 1, answerIdx)
        val c2 = cand(2)
        assertEquals(
            listOf<CallEffect>(signalFrame(CallSignalPayload(Kind.candidate, "c1", candidate = c2))),
            s.m.handle(CallEvent.Media(MediaEvent.LocalCandidate(c2))),
        )
    }

    @Test fun remoteCandidatesKeepOnlyAnsweringDevice() {
        val s = started()
        val a = CallSignalPayload(Kind.candidate, "c1", candidate = IceCandidate("A", "0", 0))
        val b = CallSignalPayload(Kind.candidate, "c1", candidate = IceCandidate("B", "0", 0))
        assertTrue(s.m.handle(RemoteSignal(a, "d1")).isEmpty())
        assertTrue(s.m.handle(RemoteSignal(b, "d2")).isEmpty())
        s.m.answered("d2")
        val fx = s.m.handle(RemoteSignal(offer(), "d2"))
        assertTrue(CallEffect.AddCandidate(b.candidate!!) in fx)
        assertFalse(CallEffect.AddCandidate(a.candidate!!) in fx)
        assertTrue(s.m.handle(RemoteSignal(a, "d1")).isEmpty())
        assertEquals(listOf<CallEffect>(CallEffect.AddCandidate(b.candidate!!)), s.m.handle(RemoteSignal(b, "d2")))
    }

    /** Brief: several callee devices signal before the answer; only the answering device's buffer survives. */
    @Test fun callerBuffersSignalsPerDevice() {
        val s = started()
        for (n in 0 until 3) s.m.handle(RemoteSignal(candPayload(n), "d1"))
        for (n in 10 until 12) s.m.handle(RemoteSignal(candPayload(n), "d2"))
        s.m.handle(RemoteSignal(candPayload(20), "d3"))
        s.m.answered("d2")
        val fx = s.m.handle(RemoteSignal(offer(), "d2"))
        assertEquals(listOf(cand(10), cand(11)), fx.filterIsInstance<CallEffect.AddCandidate>().map { it.candidate })
        // The SetRemote comes before the buffered candidates.
        assertTrue(fx.indexOfFirst { it is CallEffect.SetRemote } < fx.indexOfFirst { it is CallEffect.AddCandidate })
    }

    /** Brief: a signal from a device that is not the answering one never reaches the media engine. */
    @Test fun signalFromOtherDeviceDropped() {
        val s = started()
        s.m.answered("d1")
        // An offer from another callee device: dropped, the call goes on.
        assertTrue(s.m.handle(RemoteSignal(offer(), "d2")).isEmpty())
        assertFalse(s.m.state!!.isEnded)
        // Even a forged one does not end the call: it is not from the device we talk to.
        assertTrue(s.m.handle(RemoteSignal(offer(sdpFP = "sha-256 66:66"), "d2")).isEmpty())
        assertFalse(s.m.state!!.isEnded)
        assertTrue(s.m.handle(RemoteSignal(offer(), "d1")).hasSetRemote())
        s.m.handle(CallEvent.LocalAnswer(sdp("sha-256 11:22")))
        assertTrue(s.m.handle(RemoteSignal(candPayload(1), "d2")).isEmpty())
        assertTrue(s.m.handle(RemoteSignal(CallSignalPayload(Kind.modeChange, "c1", ceiling = DataMode.UltraLow), "d2")).isEmpty())
        assertTrue(s.m.handle(RemoteSignal(CallSignalPayload(Kind.renegotiate, "c1"), "d2")).isEmpty())
    }

    /** Brief + calls.md check 3: a payload whose callID is not the frame's (or the call's) is dropped. */
    @Test fun callIdMismatchDropped() = runTest {
        val sealer = PlainSealer()
        val m = CallMachine(outCtx(), sealer, ManualClock())
        m.handle(CallEvent.Start(bob, false, "c1"))
        // Frame for c1, payload for c2.
        val wrong = sealer.from("bob", CallSignalPayload(Kind.offer, "c2", sdp = sdp(FP), dtlsFingerprint = FP))
        assertTrue(m.ingest(CallServerEvent.Answered("c1", "d1", wrong)).none { it is CallEffect.SetRemote })
        assertFalse(m.state!!.isEnded)
        // Opened directly with another call ID: dropped.
        assertTrue(m.handle(RemoteSignal(offer().copy(callID = "c2"), "d1")).isEmpty())
        // Frames for another call are not even opened.
        assertTrue(m.ingest(CallServerEvent.Signal("c2", "d1", byteArrayOf(1, 2))).isEmpty())
        // The right one goes through.
        val right = sealer.from("bob", offer())
        assertTrue(m.handle(RemoteSignal(offer(), "d1")).hasSetRemote())
        assertTrue(m.ingest(CallServerEvent.Signal("c1", "d1", right)).isEmpty())   // an offer is never a call.signal
    }

    /** calls.md: `offer` travels in `call.answer`, so it is taken only from `call.answered`, never from `call.signal`. */
    @Test fun offerOnlyArrivesWithCallAnswered() = runTest {
        val sealer = PlainSealer()
        val m = CallMachine(outCtx(), sealer, ManualClock())
        m.handle(CallEvent.Start(bob, false, "c1"))
        m.handle(CallEvent.Server(CallServerEvent.Answered("c1", "d1")))
        assertTrue(m.ingest(CallServerEvent.Signal("c1", "d1", sealer.from("bob", offer()))).isEmpty())
        // And call.answered carries nothing but the offer.
        val n = CallMachine(outCtx(), sealer, ManualClock())
        n.handle(CallEvent.Start(bob, false, "c1"))
        assertTrue(n.ingest(CallServerEvent.Answered("c1", "d1", sealer.from("bob", candPayload(1)))).isEmpty())
        n.handle(RemoteSignal(offer(), "d1"))
        n.handle(CallEvent.LocalAnswer(sdp("sha-256 11:22")))
        assertTrue(n.handle(RemoteSignal(candPayload(2), "d1")) == listOf<CallEffect>(CallEffect.AddCandidate(cand(2))))
    }

    @Test fun ingestOpensAnsweredOffer() = runTest {
        val sealer = PlainSealer()
        val m = CallMachine(outCtx(), sealer, ManualClock())
        m.handle(CallEvent.Start(bob, false, "c1"))
        val fx = m.ingest(CallServerEvent.Answered("c1", "d1", sealer.from("bob", offer())))
        assertTrue(fx.hasAcceptOffer())
        // Another account's payload is rejected and, from the answering device, ends the call.
        val fx2 = m.ingest(CallServerEvent.Signal("c1", "d1", sealer.from("eve", candPayload(1))))
        assertEquals(CallPhase.Ended(CallEndReason.Failed), m.state?.phase)
        assertTrue(hangupFrame in fx2)
    }

    @Test fun ingestRejectsUnopenablePayloadFromOtherDeviceQuietly() = runTest {
        val sealer = PlainSealer()
        val m = CallMachine(outCtx(), sealer, ManualClock())
        m.handle(CallEvent.Start(bob, false, "c1"))
        m.ingest(CallServerEvent.Answered("c1", "d1", sealer.from("bob", offer())))
        assertTrue(m.ingest(CallServerEvent.Signal("c1", "d2", "garbage".encodeToByteArray())).isEmpty())
        assertFalse(m.state!!.isEnded)
        m.ingest(CallServerEvent.Signal("c1", "d1", "garbage".encodeToByteArray()))
        assertEquals(CallPhase.Ended(CallEndReason.Failed), m.state?.phase)
    }

    @Test fun elapsedCountsFromActive() {
        val s = started()
        s.m.answered()
        s.m.handle(RemoteSignal(offer(), "d1"))
        s.m.handle(CallEvent.LocalAnswer(sdp(FP)))
        s.clock.advance(3.0)
        s.m.handle(CallEvent.Tick)
        assertEquals(0.0, s.m.state?.elapsed)
        s.m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Connected)))
        s.clock.advance(1.0); s.m.handle(CallEvent.Tick)
        s.clock.advance(1.0); s.m.handle(CallEvent.Tick)
        assertEquals(2.0, s.m.state?.elapsed)
    }

    @Test fun hangupAfterActiveRecordsDuration() {
        val s = started(video = true, context = outCtx(network = NetworkType.Wifi))
        s.m.answered()
        s.m.handle(RemoteSignal(offer(), "d1"))
        s.m.handle(CallEvent.LocalAnswer(sdp(FP)))
        s.m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Connected)))
        s.clock.advance(7.0)
        val fx = s.m.handle(CallEvent.Hangup)
        assertTrue(hangupFrame in fx && CallEffect.End(CallEndReason.HungUp) in fx)
        assertTrue(CallEffect.RecordHistory(CallSummary.Video(7.0), outgoing = true) in fx)
        assertEquals(CallPhase.Ended(CallEndReason.HungUp), s.m.state?.phase)
        assertTrue(s.m.handle(CallEvent.Server(CallServerEvent.Ended("c1", "hangup"))).isEmpty())
    }

    @Test fun disconnectShowsReconnectingThenRecovers() {
        val s = activeMachine()
        s.m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Disconnected)))
        assertEquals(CallPhase.Reconnecting, s.m.state?.phase)
        assertEquals(CallBanner.Reconnecting, s.m.state?.banner)
        s.clock.advance(2.5)
        assertTrue(s.m.handle(CallEvent.Tick).hasRestartIce())
        s.m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Connected)))
        assertEquals(CallPhase.Active, s.m.state?.phase)
        assertEquals(null, s.m.state?.banner)
    }

    @Test fun connectionLostAfter30s() {
        val s = activeMachine()
        s.m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Disconnected)))
        s.clock.advance(30.0)
        val fx = s.m.handle(CallEvent.Tick)
        assertEquals(CallPhase.Ended(CallEndReason.ConnectionLost), s.m.state?.phase)
        assertTrue(hangupFrame in fx && CallEffect.End(CallEndReason.ConnectionLost) in fx)
    }

    @Test fun statsDriveAdaptorAndBytes() {
        val s = started(video = true, context = outCtx(network = NetworkType.Wifi))
        s.m.answered()
        s.m.handle(RemoteSignal(offer(), "d1"))
        s.m.handle(CallEvent.LocalAnswer(sdp(FP)))
        s.m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Connected)))
        val fx = s.m.handle(
            CallEvent.Stats(MediaStats(availableOutgoingBitrate = 20_000.0, bytesSent = 1000, bytesReceived = 500, packetsSent = 10, packetsReceived = 5)),
        )
        assertEquals(CoreMode.UltraLow, s.m.state?.effectiveMode)
        assertEquals(CallQuality.Weak, s.m.state?.quality)
        assertTrue(CallEffect.SetAudioMaxBitrate(10_000) in fx && CallEffect.SetVideo(null, DataMode.High) in fx)
        assertEquals(1500L + 28 * 15, s.m.state?.bytesUsed)
    }

    @Test fun userControls() {
        val s = started()
        assertTrue(CallEffect.SetMuted(true) in s.m.handle(CallEvent.SetMuted(true)))
        assertEquals(true, s.m.state?.isMuted)
        s.m.handle(CallEvent.SetSpeaker(true))
        assertEquals(true, s.m.state?.isSpeakerOn)
        s.m.handle(CallEvent.SetPreference(ModePreference.Fixed(CoreMode.DataSaver)))
        assertEquals(CoreMode.DataSaver, s.m.state?.effectiveMode)
        assertEquals(ModePreference.Fixed(CoreMode.DataSaver), s.m.state?.inCallPreference)
    }

    @Test fun remoteRejectedEndsCall() {
        val s = started()
        s.m.answered()
        val fx = s.m.handle(RemoteSignalRejected(CallSealException.WrongSender, "d1"))
        assertEquals(CallPhase.Ended(CallEndReason.Failed), s.m.state?.phase)
        assertTrue(hangupFrame in fx)
    }

    // Fix round 1 (iPhone)

    @Test fun renegotiateAnswerAccepted() {
        val s = activeMachine()
        renegotiating(s.m, s.clock)
        assertTrue(s.m.handle(RemoteSignal(reAnswer(FP), "d1")).hasAcceptAnswer())
        assertTrue(s.m.handle(RemoteSignal(reAnswer(FP), "d1")).isEmpty())
    }

    @Test fun renegotiateAnswerFromOtherDeviceIgnored() {
        val s = activeMachine()
        renegotiating(s.m, s.clock)
        assertTrue(s.m.handle(RemoteSignal(reAnswer(FP), "d2")).isEmpty())
        assertFalse(s.m.state!!.isEnded)
    }

    @Test fun renegotiateAnswerWithoutOutstandingIgnored() {
        val s = activeMachine()
        assertTrue(s.m.handle(RemoteSignal(reAnswer(FP), "d1")).isEmpty())
        assertFalse(s.m.state!!.isEnded)
    }

    @Test fun renegotiateAnswerFingerprintNotMatchingSdpEnds() {
        val s = activeMachine()
        renegotiating(s.m, s.clock)
        val fx = s.m.handle(RemoteSignal(reAnswer(FP, sdpFP = "sha-256 99:99"), "d1"))
        assertEquals(CallPhase.Ended(CallEndReason.Failed), s.m.state?.phase)
        assertTrue(hangupFrame in fx)
    }

    @Test fun renegotiateAnswerFingerprintNotPinnedEnds() {
        val s = activeMachine()
        renegotiating(s.m, s.clock)
        val fx = s.m.handle(RemoteSignal(reAnswer("sha-256 77:77"), "d1"))
        assertEquals(CallPhase.Ended(CallEndReason.Failed), s.m.state?.phase)
        assertTrue(hangupFrame in fx)
        assertFalse(fx.hasAcceptAnswer())
    }

    @Test fun modeChangeOnlyFromAnsweringDevice() {
        val s = activeMachine()
        val mc = CallSignalPayload(Kind.modeChange, "c1", ceiling = DataMode.UltraLow)
        assertTrue(s.m.handle(RemoteSignal(mc, "d2")).isEmpty())
        assertTrue(s.m.handle(RemoteSignal(mc, "d1")).isNotEmpty())
        assertEquals(CoreMode.UltraLow, s.m.state?.effectiveMode)
    }

    @Test fun remoteRejectedOnlyFromAnsweringDevice() {
        val s = started()
        assertTrue(s.m.handle(RemoteSignalRejected(CallSealException.Undecryptable, "d1")).isEmpty())
        s.m.answered()
        assertTrue(s.m.handle(RemoteSignalRejected(CallSealException.Undecryptable, "d2")).isEmpty())
        assertFalse(s.m.state!!.isEnded)
        s.m.handle(RemoteSignalRejected(CallSealException.Undecryptable, "d1"))
        assertEquals(CallPhase.Ended(CallEndReason.Failed), s.m.state?.phase)
    }

    @Test fun recoverBeforeActiveStartsClock() {
        val s = started()
        s.m.answered()
        s.m.handle(RemoteSignal(offer(), "d1"))
        s.m.handle(CallEvent.LocalAnswer(sdp(FP)))
        s.m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Disconnected)))
        s.m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Connected)))
        assertEquals(CallPhase.Active, s.m.state?.phase)
        s.clock.advance(4.0)
        s.m.handle(CallEvent.Tick)
        assertEquals(4.0, s.m.state?.elapsed)
        assertTrue(CallEffect.RecordHistory(CallSummary.Audio(4.0), outgoing = true) in s.m.handle(CallEvent.Hangup))
    }

    @Test fun endClearsBuffers() {
        val s = started()
        s.m.handle(CallEvent.Media(MediaEvent.LocalCandidate(cand(1))))
        s.m.handle(RemoteSignal(candPayload(1), "d1"))
        s.m.answered()
        assertTrue(s.m.hasBufferedState)
        s.m.handle(CallEvent.Hangup)
        assertFalse(s.m.hasBufferedState)
    }

    // lane-k: local failures

    @Test fun outgoingLocalFailureHangsUpAsFailed() {
        val s = started()
        val fx = s.m.handle(CallEvent.LocalFailure)
        assertEquals(CallPhase.Ended(CallEndReason.Failed), s.m.state?.phase)
        assertTrue(hangupFrame in fx)
    }

    /** The caller never sends an answer without a fingerprint: the callee would drop it anyway. */
    @Test fun localAnswerWithoutFingerprintEndsCall() {
        val s = started()
        s.m.answered()
        s.m.handle(RemoteSignal(offer(), "d1"))
        val fx = s.m.handle(CallEvent.LocalAnswer("v=0\r\n"))
        assertEquals(CallPhase.Ended(CallEndReason.Failed), s.m.state?.phase)
        assertTrue(fx.signal(Kind.answer) == null && hangupFrame in fx)
    }

    /** A path change while ringing does not start an ICE restart: there is no session to restart yet. */
    @Test fun pathChangeBeforeAnswerDoesNothing() {
        val s = started()
        assertTrue(s.m.handle(CallEvent.PathChanged).none { it is CallEffect.MakeOffer || it is CallEffect.SendFrame })
    }

    /** A new call does not inherit the previous call's in-call preference. */
    @Test fun nextCallStartsFromTheDefaults() {
        val s = started(context = outCtx(network = NetworkType.Wifi))
        s.m.handle(CallEvent.SetPreference(ModePreference.Fixed(CoreMode.UltraLow)))
        s.m.handle(CallEvent.Hangup)
        val fx = s.m.handle(CallEvent.Start(bob, false, "c2"))
        val invite = fx.filterIsInstance<CallEffect.SendFrame>().single { it.type == CallFrame.INVITE }
        assertEquals(DataMode.High, invite.payload?.ceiling)
        assertEquals(CoreMode.High, s.m.state?.effectiveMode)
    }
}
