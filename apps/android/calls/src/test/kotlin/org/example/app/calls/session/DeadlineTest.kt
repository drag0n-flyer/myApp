package org.example.app.calls.session

import kotlinx.coroutines.test.runTest
import org.example.app.calls.policy.IceState
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.CallSignalPayload.Kind
import org.example.app.core.calls.CallEndReason
import org.example.app.core.calls.CallPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val FP = "sha-256 AB:CD:EF"
private fun offer() = CallSignalPayload(Kind.offer, "c1", sdp = sdp(FP), dtlsFingerprint = FP)
private fun ring() = CallSignalPayload(Kind.ring, "c1", video = false)
private fun answerPayload() = CallSignalPayload(Kind.answer, "c1", sdp = sdp(FP), dtlsFingerprint = FP)

/** Ring, setup, sender-device and replay guards. Time only moves with the injected clock and `Tick`. */
class DeadlineTest {
    private fun caller(): Pair<CallMachine, ManualClock> {
        val clock = ManualClock()
        val m = CallMachine(ctx(), PlainSealer(), clock)
        m.handle(CallEvent.Start(bob, false, "c1"))
        return m to clock
    }

    private fun callee(answer: Boolean = true): Pair<CallMachine, ManualClock> {
        val clock = ManualClock()
        val m = CallMachine(ctx(), PlainSealer(), clock)
        m.handle(CallEvent.Incoming("c1", bob, "dA", ring()))
        m.handle(CallEvent.LocalOffer(sdp("sha-256 11:22:33")))
        if (answer) m.handle(CallEvent.Answer(false))
        return m to clock
    }

    private fun tickAfter(m: CallMachine, clock: ManualClock, seconds: Double): List<CallEffect> {
        clock.advance(seconds)
        return m.handle(CallEvent.Tick)
    }

    @Test fun callerUnansweredEndsMissedAndCancels() {
        val (m, clock) = caller()
        tickAfter(m, clock, 49.9)
        assertEquals(CallPhase.Ringing, m.state?.phase)
        val fx = tickAfter(m, clock, 0.1)
        assertEquals(CallPhase.Ended(CallEndReason.Missed), m.state?.phase)
        assertTrue(fx.sendsFrame(CallFrame.HANGUP))
        assertTrue(CallEffect.End(CallEndReason.Missed) in fx)
        assertTrue(fx.hasHistory())
    }

    @Test fun callerAnsweredButNoOfferFailsAfterSetupLimit() {
        val (m, clock) = caller()
        tickAfter(m, clock, 20.0)
        m.handle(CallEvent.Server(CallServerEvent.Answered("c1", "d1")))
        tickAfter(m, clock, 29.9)
        assertEquals(CallPhase.Ringing, m.state?.phase)
        val fx = tickAfter(m, clock, 0.1)
        assertEquals(CallPhase.Ended(CallEndReason.Failed), m.state?.phase)
        assertTrue(fx.sendsFrame(CallFrame.HANGUP))
        assertTrue(CallEffect.End(CallEndReason.Failed) in fx)
    }

    @Test fun callerConnectingPastSetupLimitFails() {
        val (m, clock) = caller()
        m.handle(CallEvent.Server(CallServerEvent.Answered("c1", "d1")))
        m.handle(RemoteSignal(offer(), "d1"))
        m.handle(CallEvent.LocalAnswer(sdp("sha-256 11:22:33")))
        assertEquals(CallPhase.Connecting, m.state?.phase)
        tickAfter(m, clock, 29.9)
        assertEquals(CallPhase.Connecting, m.state?.phase)
        val fx = tickAfter(m, clock, 0.1)
        assertEquals(CallPhase.Ended(CallEndReason.Failed), m.state?.phase)
        assertTrue(fx.sendsFrame(CallFrame.HANGUP))
    }

    @Test fun activeCallHasNoSetupDeadline() {
        val (m, clock) = caller()
        m.handle(CallEvent.Server(CallServerEvent.Answered("c1", "d1")))
        m.handle(RemoteSignal(offer(), "d1"))
        m.handle(CallEvent.LocalAnswer(sdp("sha-256 11:22:33")))
        m.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Connected)))
        tickAfter(m, clock, 300.0)
        assertEquals(CallPhase.Active, m.state?.phase)
    }

    @Test fun calleeConnectingPastSetupLimitFails() {
        val (m, clock) = callee()
        assertEquals(CallPhase.Connecting, m.state?.phase)
        tickAfter(m, clock, 29.9)
        assertEquals(CallPhase.Connecting, m.state?.phase)
        val fx = tickAfter(m, clock, 0.1)
        assertEquals(CallPhase.Ended(CallEndReason.Failed), m.state?.phase)
        assertTrue(fx.sendsFrame(CallFrame.HANGUP))
        assertTrue(CallEffect.End(CallEndReason.Failed) in fx)
    }

    @Test fun ringingCalleeIsNotEndedLocally() {
        val (m, clock) = callee(answer = false)
        tickAfter(m, clock, 600.0)
        assertEquals(CallPhase.Incoming, m.state?.phase)
    }

    @Test fun replayedRingForEndedCallDoesNothing() {
        val m = machine()
        m.handle(CallEvent.Incoming("c1", bob, "dA", ring()))
        m.handle(CallEvent.Decline)
        assertTrue(m.handle(CallEvent.Incoming("c1", bob, "dA", ring())).isEmpty())
        assertFalse(m.state?.phase == CallPhase.Incoming)
        // A different call still rings.
        val other = CallSignalPayload(Kind.ring, "c2", video = false)
        assertEquals(CallPhase.Incoming, m.handle(CallEvent.Incoming("c2", bob, "dA", other)).published()?.phase)
    }

    @Test fun signalWithMismatchedCredentialDeviceIsDropped() = runTest {
        val (m, _) = caller()
        val sealer = PlainSealer()
        m.handle(CallEvent.Server(CallServerEvent.Answered("c1", "d1")))
        m.handle(RemoteSignal(offer(), "d1"))
        val c = candPayload(1)
        // The credential says d2 but the server framed it from d1.
        val bad = m.ingest(CallServerEvent.Signal("c1", "d1", sealer.from("bob", "d2", c)))
        assertTrue(bad.isEmpty())
        // Matching credential device is accepted; so is a sealer that names no device.
        assertTrue(CallEffect.AddCandidate(cand(1)) in m.ingest(CallServerEvent.Signal("c1", "d1", sealer.from("bob", "d1", c))))
        assertTrue(CallEffect.AddCandidate(cand(1)) in m.ingest(CallServerEvent.Signal("c1", "d1", sealer.from("bob", c))))
    }

    @Test fun offerWithMismatchedCredentialDeviceIsDropped() = runTest {
        val (m, _) = caller()
        val sealer = PlainSealer()
        val fx = m.ingest(CallServerEvent.Answered("c1", "d1", sealer.from("bob", "d2", offer())))
        assertFalse(fx.hasSetRemote())
        assertTrue(m.ingest(CallServerEvent.Answered("c1", "d1", sealer.from("bob", "d1", offer()))).hasAcceptOffer())
    }
}
