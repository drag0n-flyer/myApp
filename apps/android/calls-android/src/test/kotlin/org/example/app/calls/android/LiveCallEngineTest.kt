package org.example.app.calls.android

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.example.app.calls.policy.AudioProfile
import org.example.app.calls.policy.IceTransportPolicy
import org.example.app.calls.policy.MediaStats
import org.example.app.calls.policy.VideoTier
import org.example.app.calls.session.CallContext
import org.example.app.calls.session.CallFrame
import org.example.app.calls.session.CallServerEvent
import org.example.app.calls.session.CallSignaling
import org.example.app.calls.session.CallStatus
import org.example.app.calls.session.Clock
import org.example.app.calls.session.IceServer
import org.example.app.calls.session.MediaEvent
import org.example.app.calls.session.MediaSession
import org.example.app.calls.session.MediaSessionFactory
import org.example.app.contracts.CallSealException
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.CallSignalSealer
import org.example.app.contracts.DataMode
import org.example.app.contracts.IceCandidate
import org.example.app.contracts.OpenedCallSignal
import org.example.app.contracts.RingOutcome
import org.example.app.core.calls.CallEndReason
import org.example.app.core.calls.CallPhase
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.CallSummary
import org.example.app.core.chats.Contact
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.NoiseSuppression
import org.example.app.core.settings.ServerPolicy
import org.example.app.core.settings.UserSettings
import org.example.app.push.MissedCallRecorder
import org.junit.Test
import org.example.app.core.calls.DataMode as CoreMode

@OptIn(ExperimentalCoroutinesApi::class)
class LiveCallEngineTest {
    private val bob = Contact("bob", "Bob", "bob")
    private val json = Json { explicitNulls = false }

    /** "<account>/<device>|<json>", opened as the real sealer would: the sender account must match. */
    private inner class PlainSealer : CallSignalSealer {
        override suspend fun seal(payload: CallSignalPayload, to: String): ByteArray =
            "me/d0|${json.encodeToString(CallSignalPayload.serializer(), payload)}".encodeToByteArray()

        override suspend fun open(data: ByteArray, from: String): OpenedCallSignal {
            val text = data.decodeToString()
            val credential = text.substringBefore('|')
            val p = runCatching { json.decodeFromString(CallSignalPayload.serializer(), text.substringAfter('|')) }
                .getOrElse { throw CallSealException.Undecryptable }
            if (credential.substringBefore('/') != from) throw CallSealException.WrongSender
            return OpenedCallSignal(p, credential.substringAfter('/'))
        }

        fun from(account: String, device: String, p: CallSignalPayload) =
            "$account/$device|${json.encodeToString(CallSignalPayload.serializer(), p)}".encodeToByteArray()
    }

    private class FakeSignaling : CallSignaling {
        override val events = MutableSharedFlow<CallServerEvent>()
        val frames = mutableListOf<String>()
        var status: suspend () -> CallStatus = { CallStatus.Ringing }
        override suspend fun invite(callID: String, to: String, video: Boolean, payload: ByteArray) { frames += CallFrame.INVITE }
        override suspend fun answer(callID: String, payload: ByteArray) { frames += CallFrame.ANSWER }
        override suspend fun signal(callID: String, payload: ByteArray) { frames += CallFrame.SIGNAL }
        override suspend fun decline(callID: String) { frames += CallFrame.DECLINE }
        var hangupBlocks = false
        override suspend fun hangup(callID: String) {
            if (hangupBlocks) kotlinx.coroutines.awaitCancellation()
            frames += CallFrame.HANGUP
        }
        override suspend fun status(callID: String): CallStatus = status()
        override suspend fun iceServers(): List<IceServer> = emptyList()
    }

    private class FakeMedia : MediaSession {
        override val events = MutableSharedFlow<MediaEvent>()
        var closed = 0
        override suspend fun configure(iceServers: List<IceServer>, policy: IceTransportPolicy, video: Boolean) = Unit
        override suspend fun makeOffer(receive: AudioProfile, iceRestart: Boolean) = SDP
        override suspend fun acceptOffer(sdp: String, send: AudioProfile, receive: AudioProfile) = SDP
        override suspend fun acceptAnswer(sdp: String, send: AudioProfile) = Unit
        override suspend fun rollback() = Unit
        override suspend fun add(candidate: IceCandidate) = Unit
        override suspend fun setAudioMaxBitrate(bps: Int) = Unit
        override suspend fun setVideo(tier: VideoTier?, ceiling: DataMode) = Unit
        override suspend fun setMuted(on: Boolean) = Unit
        override suspend fun setNoiseSuppression(mode: NoiseSuppression) = Unit
        override suspend fun stats() = MediaStats()
        override suspend fun close() { closed++ }
    }

    private class FakeController : CallController {
        val log = mutableListOf<String>()
        /** Stands in for `ForegroundServiceStartNotAllowedException` from the call service. */
        var serviceRefused = false
        override fun reportIncoming(call: CallInfo) { log += "incoming:${call.callId}" }
        override fun reportOutgoing(call: CallInfo) {
            if (serviceRefused) throw IllegalStateException("foreground service not allowed")
            log += "outgoing:${call.callId}"
        }
        override fun reportAnswered(callId: String, video: Boolean) {
            if (serviceRefused) throw IllegalStateException("foreground service not allowed")
            log += "answered:$callId"
        }
        override fun reportActive(callId: String) { log += "active:$callId" }
        override fun reportEnded(callId: String, reason: CallEndReason) { log += "ended:$callId:$reason" }
        override fun reportMissed(call: CallInfo) { log += "missed:${call.callId}" }
    }

    private class Harness(
        scope: CoroutineScope,
        peer: CallPeer?,
        media: MediaSessionFactory?,
        sealer: CallSignalSealer,
    ) {
        val signaling = FakeSignaling()
        val controller = FakeController()
        val history = mutableListOf<Pair<CallSummary, Boolean>>()
        val strangerMissed = mutableListOf<Pair<String, String>>()
        val session = FakeMedia()
        val engine = LiveCallEngine(
            scope = scope,
            signaling = signaling,
            sealer = sealer,
            media = media ?: MediaSessionFactory { session },
            peers = CallPeers { if (it == peer?.contact?.id) peer else null },
            context = { isContact -> context(isContact) },
            controller = controller,
            history = CallHistory { summary, _, outgoing -> history += summary to outgoing },
            strangers = object : MissedCallRecorder {
                override suspend fun recordMissedCall(callId: String, fromAccountId: String) {
                    strangerMissed += callId to fromAccountId
                }
            },
            clock = Clock { 0L },
            newCallId = { "c1" },
        )
    }

    private fun TestScope.harness(peer: CallPeer? = CallPeer(bob, isContact = true, isBlocked = false), media: MediaSessionFactory? = null): Harness {
        return Harness(backgroundScope, peer, media, PlainSealer()).also { it.engine.attach(); runCurrent() }
    }

    private fun ring(video: Boolean = false) = RingOutcome.Ring("c1", "bob", "Bob", video)
    private val ringPayload = CallSignalPayload(CallSignalPayload.Kind.ring, "c1", video = false)

    @Test
    fun recordsHistoryOnce() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.engine.start("bob", video = false)
        runCurrent()
        h.engine.hangUp()
        h.engine.hangUp()
        h.signaling.events.emit(CallServerEvent.Ended("c1", "hangup"))
        runCurrent()
        assertEquals(1, h.history.size)
        assertEquals(1, h.controller.log.count { it.startsWith("ended:c1") })
        assertEquals(1, h.session.closed)

        // A call already written as missed (push, then call.status) is not written again when its socket ring comes late.
        val g = harness()
        g.signaling.status = { CallStatus.Ended("missed") }
        g.engine.ring(ring())
        g.engine.ring(ring())
        runCurrent()
        g.signaling.events.emit(CallServerEvent.Ring("c1", "bob", "d1", false, PlainSealer().from("bob", "d1", ringPayload)))
        runCurrent()
        assertEquals(listOf<Pair<CallSummary, Boolean>>(CallSummary.MissedAudio to false), g.history)
        assertEquals(listOf("missed:c1"), g.controller.log)

        // A call that rang and ended here as missed is not written again when its late push says "missed".
        val m = harness()
        m.engine.ring(ring())
        runCurrent()
        m.signaling.events.emit(CallServerEvent.Ended("c1", "missed"))
        runCurrent()
        m.signaling.status = { CallStatus.Ended("missed") }
        m.engine.ring(ring())
        runCurrent()
        assertEquals(listOf<Pair<CallSummary, Boolean>>(CallSummary.MissedAudio to false), m.history)
        assertEquals(0, m.controller.log.count { it.startsWith("missed:") })
    }

    @Test
    fun statusTimeoutStillRings() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.signaling.status = { kotlinx.coroutines.awaitCancellation() }
        h.engine.ring(ring())
        advanceTimeBy(LiveCallEngine.STATUS_TIMEOUT_MS - 100)
        assertTrue(h.controller.log.isEmpty())
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("incoming:c1"), h.controller.log)
    }

    @Test
    fun shutdownEndsTheCallOnThePhone() = runTest(UnconfinedTestDispatcher()) {
        // Ringing: ended here only (no decline for the account's other devices).
        val h = harness()
        h.engine.ring(ring())
        runCurrent()
        h.engine.shutdown()
        assertEquals("ended:c1:Failed", h.controller.log.last())
        assertTrue(CallFrame.DECLINE !in h.signaling.frames)
        assertEquals(1, h.session.closed)
        // In a call: hung up, and the peer is told.
        val g = harness()
        g.engine.ring(ring())
        runCurrent()
        g.engine.answer(video = false)
        runCurrent()
        g.engine.shutdown()
        assertEquals("ended:c1:HungUp", g.controller.log.last())
        assertEquals(CallFrame.HANGUP, g.signaling.frames.last())
        assertEquals(1, g.session.closed)
        // No call: nothing to do.
        val n = harness()
        n.engine.shutdown()
        assertTrue(n.controller.log.isEmpty())
    }

    @Test
    fun shutdownEndsThePhonesCallWhenTheServerHangs() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.engine.ring(ring())
        runCurrent()
        h.engine.answer(video = false)
        runCurrent()
        h.signaling.hangupBlocks = true
        val job = launch { h.engine.shutdown() }
        advanceTimeBy(LiveCallEngine.SHUTDOWN_MS + 100)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals("ended:c1:HungUp", h.controller.log.last())
    }

    @Test
    fun endedCallDoesNotRing() = runTest(UnconfinedTestDispatcher()) {
        // Only `missed` is a missed call (as on iPhone): `hangup` cannot tell whether another device answered first.
        val h = harness()
        h.signaling.status = { CallStatus.Ended("missed") }
        h.engine.ring(ring())
        runCurrent()
        assertEquals(listOf("missed:c1"), h.controller.log)
        assertNull(h.engine.state.value)
        assertEquals(listOf<Pair<CallSummary, Boolean>>(CallSummary.MissedAudio to false), h.history)

        val g = harness()
        g.signaling.status = { CallStatus.Ended("hangup") }
        g.engine.ring(ring())
        runCurrent()
        assertTrue(g.controller.log.isEmpty())
        assertTrue(g.history.isEmpty())
        assertNull(g.engine.state.value)
    }

    @Test
    fun forgottenCallStillRings() = runTest(UnconfinedTestDispatcher()) {
        // The server no longer knows the call: unknown, so it rings and the ring deadline ends it (as on iPhone).
        val h = harness()
        h.signaling.status = { CallStatus.Unknown }
        h.engine.ring(ring())
        runCurrent()
        assertEquals(listOf("incoming:c1"), h.controller.log)
    }

    @Test
    fun pushRingRacingSocketRingRingsOnce() = runTest(UnconfinedTestDispatcher()) {
        val sealer = PlainSealer()
        val socket = CallServerEvent.Ring("c1", "bob", "d1", false, sealer.from("bob", "d1", ringPayload))
        // The socket ring lands while the push ring waits for call.status.
        val h = harness()
        val answer = CompletableDeferred<CallStatus>()
        h.signaling.status = { answer.await() }
        h.engine.ring(ring())
        runCurrent()
        h.signaling.events.emit(socket)
        runCurrent()
        answer.complete(CallStatus.Ringing)
        runCurrent()
        assertEquals(listOf("incoming:c1"), h.controller.log)
        // The push ring wins: the socket ring for the same call does nothing more.
        val g = harness()
        g.engine.ring(ring())
        runCurrent()
        g.signaling.events.emit(socket)
        runCurrent()
        assertEquals(listOf("incoming:c1"), g.controller.log)
    }

    @Test
    fun serviceRefusalFailsTheCallCleanly() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.engine.ring(ring())
        runCurrent()
        h.controller.serviceRefused = true
        h.engine.answer(video = false)
        runCurrent()
        assertEquals(CallPhase.Ended(CallEndReason.Failed), h.engine.state.value?.phase)
        assertEquals("ended:c1:Failed", h.controller.log.last())
        assertEquals(1, h.session.closed)

        val g = harness()
        g.controller.serviceRefused = true
        g.engine.start("bob", video = false)
        runCurrent()
        assertEquals(CallPhase.Ended(CallEndReason.Failed), g.engine.state.value?.phase)
        assertTrue(CallFrame.INVITE !in g.signaling.frames)
    }

    @Test
    fun strangerSocketRingIsAQuietMissedCall() = runTest(UnconfinedTestDispatcher()) {
        val sealer = PlainSealer()
        val h = harness(CallPeer(bob, isContact = false, isBlocked = false))
        h.signaling.events.emit(CallServerEvent.Ring("c1", "bob", "d1", false, sealer.from("bob", "d1", ringPayload)))
        // A forged one (sealed by someone else) records nothing.
        h.signaling.events.emit(CallServerEvent.Ring("c2", "bob", "d1", false, sealer.from("eve", "d1", ringPayload.copy(callID = "c2"))))
        runCurrent()
        assertEquals(listOf("c1" to "bob"), h.strangerMissed)
        assertTrue(h.controller.log.isEmpty())
        // Unknown accounts are strangers too; a blocked one gets nothing.
        val g = harness(null)
        g.signaling.events.emit(CallServerEvent.Ring("c1", "bob", "d1", false, sealer.from("bob", "d1", ringPayload)))
        val b = harness(CallPeer(bob, isContact = true, isBlocked = true))
        b.signaling.events.emit(CallServerEvent.Ring("c1", "bob", "d1", false, sealer.from("bob", "d1", ringPayload)))
        runCurrent()
        assertEquals(listOf("c1" to "bob"), g.strangerMissed)
        assertTrue(b.strangerMissed.isEmpty())
    }

    @Test
    fun answeredOrDeclinedCallDoesNotRing() = runTest(UnconfinedTestDispatcher()) {
        for (status in listOf(CallStatus.Answered, CallStatus.Ended("answered_elsewhere"), CallStatus.Ended("declined"))) {
            val h = harness()
            h.signaling.status = { status }
            h.engine.ring(ring())
            runCurrent()
            assertTrue(h.controller.log.isEmpty(), "$status")
            assertTrue(h.history.isEmpty(), "$status")
        }
    }

    @Test
    fun ringingCallRings() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.engine.ring(ring())
        runCurrent()
        assertEquals(listOf("incoming:c1"), h.controller.log)
        assertEquals(CallPhase.Incoming, h.engine.state.value?.phase)
        h.engine.answer(video = false)
        runCurrent()
        assertEquals("answered:c1", h.controller.log.last())
        assertTrue(CallFrame.ANSWER in h.signaling.frames)
    }

    @Test
    fun statusFailureStillRings() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.signaling.status = { throw IllegalStateException("offline") }
        h.engine.ring(ring())
        runCurrent()
        assertEquals(listOf("incoming:c1"), h.controller.log)
    }

    @Test
    fun strangerSocketRingDoesNotRing() = runTest(UnconfinedTestDispatcher()) {
        val sealer = PlainSealer()
        for (peer in listOf(CallPeer(bob, isContact = false, isBlocked = false), CallPeer(bob, isContact = true, isBlocked = true), null)) {
            val h = harness(peer)
            val p = CallSignalPayload(CallSignalPayload.Kind.ring, "c1", video = false)
            h.signaling.events.emit(CallServerEvent.Ring("c1", "bob", "d1", false, sealer.from("bob", "d1", p)))
            runCurrent()
            assertTrue(h.controller.log.isEmpty())
        }
    }

    @Test
    fun socketRingChecksSenderAndDevice() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        val sealer = PlainSealer()
        val p = CallSignalPayload(CallSignalPayload.Kind.ring, "c1", video = false)
        // Sealed by someone else, by another of Bob's devices, or for another call: dropped.
        h.signaling.events.emit(CallServerEvent.Ring("c1", "bob", "d1", false, sealer.from("eve", "d1", p)))
        h.signaling.events.emit(CallServerEvent.Ring("c1", "bob", "d1", false, sealer.from("bob", "d9", p)))
        h.signaling.events.emit(CallServerEvent.Ring("c2", "bob", "d1", false, sealer.from("bob", "d1", p)))
        runCurrent()
        assertTrue(h.controller.log.isEmpty())
        h.signaling.events.emit(CallServerEvent.Ring("c1", "bob", "d1", false, sealer.from("bob", "d1", p)))
        runCurrent()
        assertEquals(listOf("incoming:c1"), h.controller.log)
    }

    @Test
    fun mediaFailureEndsTheCall() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(media = MediaSessionFactory { throw UnsupportedOperationException() })
        h.engine.start("bob", video = false)
        runCurrent()
        assertEquals(CallPhase.Ended(CallEndReason.Failed), h.engine.state.value?.phase)
        assertEquals(CallFrame.HANGUP, h.signaling.frames.last())
        h.engine.reset()
        assertNull(h.engine.state.value)
    }

    private companion object {
        const val SDP = "v=0\r\na=fingerprint:sha-256 AA:BB\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"

        val server = ServerPolicy(
            defaultPreference = ModePreference.Auto, maximum = CoreMode.High,
            defaultIPHiding = IPHiding.NonContacts, requiresInvite = true, host = "h",
        )

        fun context(isContact: Boolean) = CallContext(server, UserSettings(ipHiding = IPHiding.NonContacts), NetworkType.Wifi, isContact)
    }
}
