package org.example.app.calls.session

import org.example.app.calls.policy.IceState
import org.example.app.calls.policy.IceTransportPolicy
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.CallSignalPayload.Kind
import org.example.app.contracts.DataMode
import org.example.app.core.calls.CallEndReason
import org.example.app.core.calls.CallPhase
import org.example.app.core.calls.DataModePolicy
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.CallSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val MY_FP = "sha-256 11:22:33"
private const val CALLER_FP = "sha-256 AB:CD:EF"
private fun ring(video: Boolean, ceiling: DataMode? = null) = CallSignalPayload(Kind.ring, "c1", video = video, ceiling = ceiling)
private fun answerPayload(f: String = CALLER_FP, sdpFP: String? = null, ceiling: DataMode? = null) =
    CallSignalPayload(Kind.answer, "c1", sdp = sdp(sdpFP ?: f), dtlsFingerprint = f, ceiling = ceiling)
private val wifiCeiling: DataMode =
    DataMode.valueOf(DataModePolicy.ceiling(server, ctx().settings.call, NetworkType.Wifi, null).name)

/** Mirrors the iPhone's IncomingCallTests, plus the Android brief's callee cases. */
class IncomingCallTest {
    private class Ringing(val m: CallMachine, val clock: ManualClock, val fx: List<CallEffect>)

    private fun ringing(video: Boolean = true, context: CallContext = ctx(), ceiling: DataMode? = null, device: String = "dA"): Ringing {
        val clock = ManualClock()
        val m = CallMachine(context, PlainSealer(), clock)
        val fx = m.handle(CallEvent.Incoming("c1", bob, device, ring(video, ceiling)))
        return Ringing(m, clock, fx)
    }

    /** Ringing, local offer ready, answered, caller's answer accepted, ICE connected. */
    private fun active(video: Boolean = false): Ringing {
        val r = ringing(video)
        r.m.handle(CallEvent.LocalOffer(sdp(MY_FP)))
        r.m.handle(CallEvent.Answer(video))
        r.m.handle(CallEvent.Remote(answerPayload(), "dA"))
        r.m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Connected)))
        return r
    }

    private fun offerFrame(fx: List<CallEffect>) =
        fx.filterIsInstance<CallEffect.SendFrame>().firstOrNull { it.type == CallFrame.ANSWER }

    // lane-k

    @Test fun pushRingBindsToTheFirstAnsweringDevice() {
        val r = ringing(video = false, device = "")
        r.m.handle(CallEvent.LocalOffer(sdp(MY_FP)))
        r.m.handle(CallEvent.Answer(false))
        assertTrue(r.m.handle(CallEvent.Remote(answerPayload(), "dZ")).hasAcceptAnswer())
        assertTrue(r.m.handle(CallEvent.Remote(candPayload(1), "dOther")).isEmpty())
        assertTrue(CallEffect.AddCandidate(cand(1)) in r.m.handle(CallEvent.Remote(candPayload(1), "dZ")))
    }

    @Test fun knownDeviceStaysBound() {
        val r = ringing()
        r.m.handle(CallEvent.LocalOffer(sdp(MY_FP)))
        r.m.handle(CallEvent.Answer(false))
        assertFalse(r.m.handle(CallEvent.Remote(answerPayload(), "dOther")).hasAcceptAnswer())
    }

    @Test fun incomingPrewarmsAndOffersLocally() {
        val r = ringing(video = true)
        val cfg = CallEffect.ConfigureMedia(IceTransportPolicy.All, video = true)
        val make = CallEffect.CreateOffer(profile(wifiCeiling))
        assertTrue(cfg in r.fx && make in r.fx)
        assertEquals(CallPhase.Incoming, r.m.state?.phase)
        assertEquals(true, r.m.state?.isVideoCall)
        assertTrue(r.fx.none { it is CallEffect.SendFrame })
        assertTrue(r.fx.indexOf(cfg) < r.fx.indexOf(make))
    }

    /** Brief: the callee makes the offer while ringing and sends it in `call.answer` when the user answers. */
    @Test fun calleeMakesOffer() {
        val r = ringing(video = false)
        assertTrue(r.fx.any { it is CallEffect.CreateOffer })
        assertTrue(r.m.handle(CallEvent.LocalOffer(sdp(MY_FP))).isEmpty())
        val fx = r.m.handle(CallEvent.Answer(false))
        assertEquals(
            CallEffect.SendFrame(
                CallFrame.ANSWER,
                CallSignalPayload(Kind.offer, "c1", sdp = sdp(MY_FP), dtlsFingerprint = MY_FP, ceiling = wifiCeiling),
            ),
            offerFrame(fx),
        )
    }

    @Test fun mobileDataStartsVideoOff() {
        val r = ringing(video = true, context = ctx(network = NetworkType.Mobile))
        assertTrue(CallEffect.ConfigureMedia(IceTransportPolicy.All, video = false) in r.fx)
    }

    @Test fun strangerIncomingForcesRelay() {
        val r = ringing(video = false, context = ctx(isContact = false))
        assertTrue(CallEffect.ConfigureMedia(IceTransportPolicy.Relay, video = false) in r.fx)
    }

    @Test fun answerSendsOffer() {
        val r = ringing()
        assertTrue(r.m.handle(CallEvent.LocalOffer(sdp(MY_FP))).isEmpty())
        assertTrue(r.m.handle(CallEvent.Media(MediaEvent.LocalCandidate(cand(1)))).isEmpty())
        assertTrue(r.m.handle(CallEvent.Media(MediaEvent.LocalCandidate(cand(2)))).isEmpty())
        val fx = r.m.handle(CallEvent.Answer(true))
        val offer = CallSignalPayload(Kind.offer, "c1", sdp = sdp(MY_FP), dtlsFingerprint = MY_FP, ceiling = wifiCeiling)
        assertEquals(CallEffect.SendFrame(CallFrame.ANSWER, offer), offerFrame(fx))
        val tail = fx.drop(fx.indexOfFirst { it == offerFrame(fx) } + 1).filter { it is CallEffect.SendFrame && it.type == CallFrame.SIGNAL }
        assertEquals(listOf<CallEffect>(signalFrame(candPayload(1)), signalFrame(candPayload(2))), tail)
        assertEquals(CallPhase.Connecting, r.m.state?.phase)
        assertEquals(true, r.m.state?.isVideoOn)
        assertEquals(listOf<CallEffect>(signalFrame(candPayload(3))), r.m.handle(CallEvent.Media(MediaEvent.LocalCandidate(cand(3)))))
    }

    @Test fun answerBeforeLocalOfferWaits() {
        val r = ringing()
        assertTrue(offerFrame(r.m.handle(CallEvent.Answer(false))) == null)
        val fx = r.m.handle(CallEvent.LocalOffer(sdp(MY_FP)))
        val p = offerFrame(fx)?.payload
        assertEquals(Kind.offer, p?.kind)
        assertEquals(MY_FP, p?.dtlsFingerprint)
    }

    @Test fun localOfferWithoutFingerprintEndsCall() {
        val r = ringing()
        r.m.handle(CallEvent.Answer(false))
        val fx = r.m.handle(CallEvent.LocalOffer("v=0\r\n"))
        assertEquals(CallPhase.Ended(CallEndReason.Failed), r.m.state?.phase)
        assertTrue(hangupFrame in fx)
        assertTrue(offerFrame(fx) == null)
    }

    @Test fun remoteAnswerAccepted() {
        val r = ringing()
        r.m.handle(CallEvent.LocalOffer(sdp(MY_FP)))
        r.m.handle(CallEvent.Answer(false))
        val fx = r.m.handle(CallEvent.Remote(answerPayload(), "dA"))
        assertTrue(CallEffect.AcceptAnswer(sdp(CALLER_FP), profile(DataMode.valueOf(r.m.state!!.effectiveMode.name))) in fx)
    }

    @Test fun remoteAnswerChecks() {
        val r = ringing()
        r.m.handle(CallEvent.LocalOffer(sdp(MY_FP)))
        r.m.handle(CallEvent.Answer(false))
        assertTrue(r.m.handle(CallEvent.Remote(answerPayload(), "dB")).isEmpty())
        assertFalse(r.m.state!!.isEnded)
        val fx = r.m.handle(CallEvent.Remote(answerPayload(sdpFP = "sha-256 99:99"), "dA"))
        assertEquals(CallPhase.Ended(CallEndReason.Failed), r.m.state?.phase)
        assertTrue(hangupFrame in fx && !fx.hasSetRemote())
        val n = ringing()
        n.m.handle(CallEvent.LocalOffer(sdp(MY_FP)))
        assertTrue(n.m.handle(CallEvent.Remote(answerPayload(), "dA")).isEmpty())
    }

    @Test fun answerFingerprintComparedCaseInsensitively() {
        val r = ringing()
        r.m.handle(CallEvent.LocalOffer(sdp(MY_FP)))
        r.m.handle(CallEvent.Answer(false))
        val fx = r.m.handle(CallEvent.Remote(answerPayload(f = "SHA-256 ab:cd:ef", sdpFP = CALLER_FP), "dA"))
        assertTrue(fx.hasAcceptAnswer())
    }

    @Test fun callerCandidatesBoundToCallerDevice() {
        val r = ringing()
        assertTrue(r.m.handle(CallEvent.Remote(candPayload(1), "dA")).isEmpty())
        assertTrue(r.m.handle(CallEvent.Remote(candPayload(2), "dB")).isEmpty())
        r.m.handle(CallEvent.LocalOffer(sdp(MY_FP)))
        r.m.handle(CallEvent.Answer(false))
        val fx = r.m.handle(CallEvent.Remote(answerPayload(), "dA"))
        assertTrue(CallEffect.AddCandidate(cand(1)) in fx && CallEffect.AddCandidate(cand(2)) !in fx)
        assertEquals(listOf<CallEffect>(CallEffect.AddCandidate(cand(3))), r.m.handle(CallEvent.Remote(candPayload(3), "dA")))
        assertTrue(r.m.handle(CallEvent.Remote(candPayload(4), "dB")).isEmpty())
    }

    @Test fun answeredElsewhere() {
        val r = ringing()
        val fx = r.m.handle(CallEvent.Server(CallServerEvent.Ended("c1", "answered_elsewhere")))
        assertEquals(CallPhase.Ended(CallEndReason.AnsweredElsewhere), r.m.state?.phase)
        assertTrue(CallEffect.End(CallEndReason.AnsweredElsewhere) in fx)
        assertFalse(fx.hasHistory())
    }

    /** Brief: another device answered; this one closes its prewarmed media and sends nothing. */
    @Test fun answeredElsewhereCloses() {
        val r = ringing()
        r.m.handle(CallEvent.LocalOffer(sdp(MY_FP)))
        r.m.handle(CallEvent.Media(MediaEvent.LocalCandidate(cand(1))))
        val fx = r.m.handle(CallEvent.Server(CallServerEvent.Ended("c1", "answered_elsewhere")))
        assertTrue(CallEffect.End(CallEndReason.AnsweredElsewhere) in fx)
        assertTrue(fx.none { it is CallEffect.SendFrame })
        assertFalse(r.m.hasBufferedState)
        assertTrue(r.m.handle(CallEvent.Answer(false)).isEmpty())
    }

    /** Brief: a push-woken callee asks `call.status`; an ended call ends locally, silently and once. */
    @Test fun statusEndedAfterPushEnds() {
        val r = ringing(video = true, device = "")
        assertTrue(r.m.handle(CallEvent.Status("c1", CallStatus.Ringing)).isEmpty())
        assertTrue(r.m.handle(CallEvent.Status("other", CallStatus.Ended("missed"))).isEmpty())
        val fx = r.m.handle(CallEvent.Status("c1", CallStatus.Ended("missed")))
        assertEquals(CallPhase.Ended(CallEndReason.Missed), r.m.state?.phase)
        assertTrue(CallEffect.End(CallEndReason.Missed) in fx)
        assertTrue(fx.none { it is CallEffect.SendFrame })
        assertTrue(CallEffect.RecordHistory(CallSummary.MissedVideo, outgoing = false) in fx)
        assertTrue(r.m.handle(CallEvent.Status("c1", CallStatus.Ended("missed"))).isEmpty())
        // Answered on another device while this one was asleep.
        val a = ringing(device = "")
        val fx2 = a.m.handle(CallEvent.Status("c1", CallStatus.Answered))
        assertEquals(CallPhase.Ended(CallEndReason.AnsweredElsewhere), a.m.state?.phase)
        assertFalse(fx2.hasHistory())
        // Once this device answered, "answered" is its own answer.
        val b = ringing()
        b.m.handle(CallEvent.Answer(false))
        assertTrue(b.m.handle(CallEvent.Status("c1", CallStatus.Answered)).isEmpty())
        assertFalse(b.m.state!!.isEnded)
    }

    @Test fun declineByUser() {
        val r = ringing(video = true)
        val fx = r.m.handle(CallEvent.Decline)
        assertTrue(CallEffect.SendFrame(CallFrame.DECLINE) in fx && CallEffect.End(CallEndReason.Declined) in fx)
        assertEquals(CallPhase.Ended(CallEndReason.Declined), r.m.state?.phase)
        assertTrue(CallEffect.RecordHistory(CallSummary.MissedVideo, outgoing = false) in fx)
        val a = ringing(video = false)
        assertTrue(CallEffect.RecordHistory(CallSummary.MissedAudio, outgoing = false) in a.m.handle(CallEvent.Decline))
    }

    @Test fun missedIncoming() {
        val r = ringing(video = true)
        val fx = r.m.handle(CallEvent.Server(CallServerEvent.Ended("c1", "missed")))
        assertEquals(CallPhase.Ended(CallEndReason.Missed), r.m.state?.phase)
        assertTrue(CallEffect.RecordHistory(CallSummary.MissedVideo, outgoing = false) in fx)
    }

    @Test fun answeredCallRecordsDuration() {
        val r = active()
        r.clock.advance(9.0)
        assertTrue(CallEffect.RecordHistory(CallSummary.Audio(9.0), outgoing = false) in r.m.handle(CallEvent.Hangup))
    }

    @Test fun peerCeilingFromRingLimitsSendMode() {
        val r = ringing(ceiling = DataMode.DataSaver)
        r.m.handle(CallEvent.LocalOffer(sdp(MY_FP)))
        r.m.handle(CallEvent.Answer(false))
        val fx = r.m.handle(CallEvent.Remote(answerPayload(), "dA"))
        assertTrue(CallEffect.AcceptAnswer(sdp(CALLER_FP), profile(DataMode.DataSaver)) in fx)
    }

    // Renegotiation on the callee

    @Test fun calleeRequestsRestart() {
        val r = active()
        r.m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Disconnected)))
        r.clock.advance(2.5)
        assertTrue(signalFrame(CallSignalPayload(Kind.renegotiate, "c1")) in r.m.handle(CallEvent.Tick))
    }

    /** Brief: a callee that needs a restart sends `renegotiate` without `sdp`; the caller answers with an ICE-restart offer. */
    @Test fun calleeAsksRestartWithEmptyRenegotiate() {
        val r = active()
        val fx = r.m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Failed)))
        val req = fx.signal(Kind.renegotiate)
        assertNotNull(req)
        assertEquals(CallSignalPayload(Kind.renegotiate, "c1"), req)
        assertTrue(fx.none { it is CallEffect.MakeOffer })   // the callee never makes the restart offer itself
        // At the caller, that empty renegotiate starts an ICE-restart offer.
        val caller = CallMachine(ctx(), PlainSealer(), ManualClock())
        caller.handle(CallEvent.Start(bob, false, "c1"))
        caller.handle(CallEvent.Server(CallServerEvent.Answered("c1", "d1")))
        caller.handle(CallEvent.Remote(CallSignalPayload(Kind.offer, "c1", sdp = sdp(MY_FP), dtlsFingerprint = MY_FP), "d1"))
        caller.handle(CallEvent.LocalAnswer(sdp(CALLER_FP)))
        assertTrue(caller.handle(CallEvent.Remote(req, "d1")).hasRestartIce())
    }

    @Test fun calleeAnswersRenegotiateWithPinnedFingerprint() {
        val r = active()
        val re = CallSignalPayload(Kind.renegotiate, "c1", sdp = sdp(CALLER_FP), dtlsFingerprint = CALLER_FP)
        assertTrue(r.m.handle(CallEvent.Remote(re, "dB")).isEmpty())
        assertTrue(r.m.handle(CallEvent.Remote(re, "dA")).hasAcceptOffer())
        val out = r.m.handle(CallEvent.LocalAnswer(sdp(MY_FP)))
        assertTrue(
            signalFrame(CallSignalPayload(Kind.renegotiateAnswer, "c1", sdp = sdp(MY_FP), dtlsFingerprint = MY_FP, ceiling = wifiCeiling)) in out,
        )
    }

    @Test fun calleeRejectsRenegotiateWithWrongFingerprint() {
        for ((claimed, sdpFP) in listOf("sha-256 77:77" to "sha-256 77:77", CALLER_FP to "sha-256 99:99")) {
            val r = active()
            val re = CallSignalPayload(Kind.renegotiate, "c1", sdp = sdp(sdpFP), dtlsFingerprint = claimed)
            val fx = r.m.handle(CallEvent.Remote(re, "dA"))
            assertEquals(CallPhase.Ended(CallEndReason.Failed), r.m.state?.phase)
            assertTrue(hangupFrame in fx && !fx.hasAcceptOffer())
        }
    }

    @Test fun calleeIgnoresRenegotiateBeforeActive() {
        val r = ringing()
        val re = CallSignalPayload(Kind.renegotiate, "c1", sdp = sdp(CALLER_FP), dtlsFingerprint = CALLER_FP)
        assertTrue(r.m.handle(CallEvent.Remote(re, "dA")).isEmpty())
    }

    @Test fun callerRespondsToRestartRequest() {
        val m = machine()
        m.handle(CallEvent.Start(bob, false, "c1"))
        m.handle(CallEvent.Server(CallServerEvent.Answered("c1", "d1")))
        m.handle(CallEvent.Remote(CallSignalPayload(Kind.offer, "c1", sdp = sdp(MY_FP), dtlsFingerprint = MY_FP), "d1"))
        m.handle(CallEvent.LocalAnswer(sdp(CALLER_FP)))
        val req = CallSignalPayload(Kind.renegotiate, "c1")
        assertTrue(m.handle(CallEvent.Remote(req, "d2")).isEmpty())
        assertTrue(m.handle(CallEvent.Remote(req, "d1")).hasRestartIce())
        val withSDP = CallSignalPayload(Kind.renegotiate, "c1", sdp = sdp(MY_FP), dtlsFingerprint = MY_FP)
        assertTrue(m.handle(CallEvent.Remote(withSDP, "d1")).isEmpty())
    }

    // Buffers

    @Test fun endClearsCalleeBuffers() {
        val r = ringing()
        r.m.handle(CallEvent.Media(MediaEvent.LocalCandidate(cand(1))))
        r.m.handle(CallEvent.Remote(candPayload(2), "dA"))
        assertTrue(r.m.hasBufferedState)
        r.m.handle(CallEvent.Decline)
        assertFalse(r.m.hasBufferedState)
    }

    @Test fun earlyCandidatesCappedPerDevice() {
        val r = ringing()
        for (n in 0 until 200) r.m.handle(CallEvent.Remote(candPayload(n), "dA"))
        r.m.handle(CallEvent.LocalOffer(sdp(MY_FP)))
        r.m.handle(CallEvent.Answer(false))
        val fx = r.m.handle(CallEvent.Remote(answerPayload(), "dA"))
        assertEquals(CallMachine.MAX_EARLY_CANDIDATES_PER_DEVICE, fx.count { it is CallEffect.AddCandidate })
    }

    @Test fun earlyDevicesCappedOnCaller() {
        val total = CallMachine.MAX_EARLY_DEVICES + 12
        val offer = CallSignalPayload(Kind.offer, "c1", sdp = sdp(MY_FP), dtlsFingerprint = MY_FP)
        val m = machine()
        m.handle(CallEvent.Start(bob, false, "c1"))
        for (d in 0 until total) m.handle(CallEvent.Remote(candPayload(d), "d$d"))
        val last = "d${total - 1}"
        m.handle(CallEvent.Server(CallServerEvent.Answered("c1", last)))
        assertTrue(m.handle(CallEvent.Remote(offer, last)).none { it is CallEffect.AddCandidate })
        val n = machine()
        n.handle(CallEvent.Start(bob, false, "c1"))
        for (d in 0 until total) n.handle(CallEvent.Remote(candPayload(d), "d$d"))
        n.handle(CallEvent.Server(CallServerEvent.Answered("c1", "d0")))
        assertTrue(CallEffect.AddCandidate(cand(0)) in n.handle(CallEvent.Remote(offer, "d0")))
    }

    @Test fun localPendingCandidatesCapped() {
        val r = ringing()
        for (n in 0 until 500) r.m.handle(CallEvent.Media(MediaEvent.LocalCandidate(cand(n))))
        r.m.handle(CallEvent.LocalOffer(sdp(MY_FP)))
        val fx = r.m.handle(CallEvent.Answer(false))
        assertEquals(CallMachine.MAX_PENDING_LOCAL_CANDIDATES, fx.count { it is CallEffect.SendFrame && it.type == CallFrame.SIGNAL })
    }

    // Fix round 1 (iPhone)

    @Test fun calleeVideoStartOffUsesEffectiveCeiling() {
        val r = ringing(video = true, ceiling = DataMode.DataSaver)
        assertTrue(CallEffect.ConfigureMedia(IceTransportPolicy.All, video = false) in r.fx)
    }

    private fun callerConnected(clock: ManualClock = ManualClock()): CallMachine {
        val m = CallMachine(ctx(), PlainSealer(), clock)
        m.handle(CallEvent.Start(bob, false, "c1"))
        m.handle(CallEvent.Server(CallServerEvent.Answered("c1", "d1")))
        m.handle(CallEvent.Remote(CallSignalPayload(Kind.offer, "c1", sdp = sdp(MY_FP), dtlsFingerprint = MY_FP), "d1"))
        m.handle(CallEvent.LocalAnswer(sdp(CALLER_FP)))
        return m
    }

    @Test fun callerIgnoresRestartRequestWhileOfferOutstanding() {
        val m = callerConnected()
        val req = CallSignalPayload(Kind.renegotiate, "c1")
        assertTrue(m.handle(CallEvent.Remote(req, "d1")).isNotEmpty())
        assertTrue(m.handle(CallEvent.Remote(req, "d1")).isEmpty())
    }

    @Test fun callerRateLimitsRequestsAndGatesOwnRestart() {
        val clock = ManualClock()
        val m = callerConnected(clock)
        m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Connected)))
        val req = CallSignalPayload(Kind.renegotiate, "c1")
        m.handle(CallEvent.Remote(req, "d1"))
        m.handle(CallEvent.LocalOffer(sdp(CALLER_FP)))
        m.handle(CallEvent.Remote(CallSignalPayload(Kind.renegotiateAnswer, "c1", sdp = sdp(MY_FP), dtlsFingerprint = MY_FP), "d1"))
        clock.advance(1.0)
        assertTrue(m.handle(CallEvent.Remote(req, "d1")).isEmpty())
        clock.advance(4.0)
        assertTrue(m.handle(CallEvent.Remote(req, "d1")).isNotEmpty())
        m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Disconnected)))
        clock.advance(2.5)
        assertFalse(m.handle(CallEvent.Tick).any { it is CallEffect.MakeOffer })
        clock.advance(5.0)
        assertTrue(m.handle(CallEvent.Tick).any { it is CallEffect.MakeOffer })
    }

    @Test fun callerFailsIfRestartOfferHasNoFingerprint() {
        val m = callerConnected()
        m.handle(CallEvent.Remote(CallSignalPayload(Kind.renegotiate, "c1"), "d1"))
        val fx = m.handle(CallEvent.LocalOffer("v=0\r\n"))
        assertEquals(CallPhase.Ended(CallEndReason.Failed), m.state?.phase)
        assertTrue(hangupFrame in fx)
    }

    @Test fun calleeDoesNotAcceptSecondOfferBeforeAnswering() {
        val r = active()
        val re = CallSignalPayload(Kind.renegotiate, "c1", sdp = sdp(CALLER_FP), dtlsFingerprint = CALLER_FP)
        assertTrue(r.m.handle(CallEvent.Remote(re, "dA")).hasAcceptOffer())
        assertTrue(r.m.handle(CallEvent.Remote(re, "dA")).isEmpty())
        r.m.handle(CallEvent.LocalAnswer(sdp(MY_FP)))
        assertTrue(r.m.handle(CallEvent.Remote(re, "dA")).hasAcceptOffer())
    }

    @Test fun calleeDoesNotRequestWhileAnsweringAnOffer() {
        val r = active()
        val re = CallSignalPayload(Kind.renegotiate, "c1", sdp = sdp(CALLER_FP), dtlsFingerprint = CALLER_FP)
        r.m.handle(CallEvent.Remote(re, "dA"))
        r.m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Disconnected)))
        r.clock.advance(2.5)
        assertTrue(r.m.handle(CallEvent.Tick).none { it is CallEffect.SendFrame })
    }

    @Test fun simultaneousRestartsConverge() {
        val callerClock = ManualClock()
        val calleeClock = ManualClock()
        val caller = CallMachine(ctx(), PlainSealer(), callerClock)
        val callee = CallMachine(ctx(), PlainSealer(), calleeClock)
        caller.handle(CallEvent.Start(bob, false, "c1"))
        callee.handle(CallEvent.Incoming("c1", bob, "caller-dev", ring(false)))
        callee.handle(CallEvent.LocalOffer(sdp(MY_FP)))
        val offerPayload = offerFrame(callee.handle(CallEvent.Answer(false)))?.payload ?: fail("no offer")
        caller.handle(CallEvent.Server(CallServerEvent.Answered("c1", "callee-dev")))
        caller.handle(CallEvent.Remote(offerPayload, "callee-dev"))
        val answer = caller.handle(CallEvent.LocalAnswer(sdp(CALLER_FP))).signal(Kind.answer) ?: fail("no answer")
        callee.handle(CallEvent.Remote(answer, "caller-dev"))
        caller.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Connected)))
        callee.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Connected)))
        caller.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Disconnected)))
        callee.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Disconnected)))
        callerClock.advance(2.5); calleeClock.advance(2.5)
        assertTrue(caller.handle(CallEvent.Tick).hasRestartIce())
        val request = callee.handle(CallEvent.Tick).signal(Kind.renegotiate) ?: fail("no request")
        val restartOffer = caller.handle(CallEvent.LocalOffer(sdp(CALLER_FP))).signal(Kind.renegotiate) ?: fail("no restart offer")
        assertTrue(caller.handle(CallEvent.Remote(request, "callee-dev")).isEmpty())
        assertTrue(callee.handle(CallEvent.Remote(restartOffer, "caller-dev")).hasAcceptOffer())
        val reply = callee.handle(CallEvent.LocalAnswer(sdp(MY_FP))).signal(Kind.renegotiateAnswer) ?: fail("no reply")
        assertTrue(caller.handle(CallEvent.Remote(reply, "callee-dev")).hasAcceptAnswer())
    }

    // lane-k: local failures

    /** A ringing callee failing on its own never declines: the other devices keep ringing. */
    @Test fun ringingCalleeFailureIsLocalAndSilent() {
        val r = ringing()
        val fx = r.m.handle(CallEvent.LocalFailure)
        assertEquals(CallPhase.Ended(CallEndReason.Failed), r.m.state?.phase)
        assertTrue(fx.none { it is CallEffect.SendFrame })
        assertEquals(1, fx.count { it is CallEffect.RecordHistory })
    }

    @Test fun answeredCalleeFailureHangsUp() {
        val r = ringing(video = false)
        r.m.handle(CallEvent.Answer(false))
        val fx = r.m.handle(CallEvent.LocalFailure)
        assertTrue(hangupFrame in fx)
        assertEquals(CallPhase.Ended(CallEndReason.Failed), r.m.state?.phase)
    }

    @Test fun ringForAnotherCallIDIsIgnored() {
        val m = machine()
        assertTrue(m.handle(CallEvent.Incoming("c1", bob, "dA", CallSignalPayload(Kind.ring, "c2"))).isEmpty())
        assertTrue(m.handle(CallEvent.Incoming("c1", bob, "dA", CallSignalPayload(Kind.offer, "c1"))).isEmpty())
        assertEquals(null, m.state)
    }
}
