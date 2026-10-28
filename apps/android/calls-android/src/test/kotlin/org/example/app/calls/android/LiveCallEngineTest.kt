package org.example.app.calls.android

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
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
        override suspend fun hangup(callID: String) { frames += CallFrame.HANGUP }
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
        override fun reportIncoming(call: CallInfo) { log += "incoming:${call.callId}" }
        override fun reportOutgoing(call: CallInfo) { log += "outgoing:${call.callId}" }
        override fun reportAnswered(callId: String, video: Boolean) { log += "answered:$callId" }
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
            clock = Clock { 0L },
            newCallId = { "c1" },
        )
    }

    private fun TestScope.harness(peer: CallPeer? = CallPeer(bob, isContact = true, isBlocked = false), media: MediaSessionFactory? = null): Harness {
        return Harness(backgroundScope, peer, media, PlainSealer()).also { it.engine.attach(); runCurrent() }
    }

    private fun ring(video: Boolean = false) = RingOutcome.Ring("c1", "bob", "Bob", video)

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
    }

    @Test
    fun endedCallDoesNotRing() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.signaling.status = { CallStatus.Ended("hangup") }
        h.engine.ring(ring())
        runCurrent()
        assertEquals(listOf("missed:c1"), h.controller.log)
        assertNull(h.engine.state.value)
        assertEquals(listOf<Pair<CallSummary, Boolean>>(CallSummary.MissedAudio to false), h.history)
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
