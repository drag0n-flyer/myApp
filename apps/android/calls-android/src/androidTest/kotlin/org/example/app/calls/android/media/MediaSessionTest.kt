package org.example.app.calls.android.media

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.app.calls.policy.AudioProfile
import org.example.app.calls.policy.IceState
import org.example.app.calls.policy.IceTransportPolicy
import org.example.app.calls.session.MediaEvent
import org.example.app.contracts.DataMode
import org.example.app.contracts.IceCandidate
import org.example.app.core.settings.NoiseSuppression
import org.example.app.core.settings.UserSettings
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two [WebRtcMediaSession]s in one process, connected over host candidates. Runs on the API 29 and API 36
 * emulators: `./gradlew :calls-android:pixel6Api29DebugAndroidTest`. The iPhone's LoopbackTests (CallMedia).
 */
@RunWith(AndroidJUnit4::class)
class MediaSessionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val iceStates = ConcurrentHashMap<String, MutableList<IceState>>()
    private val candidates = ConcurrentHashMap<String, MutableList<IceCandidate>>()
    private val relay = CandidateRelay()

    @Before
    fun grantMicrophone() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        CaptureHook.enabled = false
        CaptureHook.tone = false
    }

    private fun session(noise: NoiseSuppression = NoiseSuppression.Standard) =
        WebRtcMediaSession(context, UserSettings(noiseSuppression = noise))

    /** Records every event of [s] under [name] and forwards its candidates to [peer]. */
    private fun pump(name: String, s: WebRtcMediaSession, peer: String?) {
        scope.launch {
            s.events.collect { e ->
                when (e) {
                    is MediaEvent.IceStateChanged -> iceStates.getOrPut(name) { CopyOnWriteArrayList() } += e.state
                    is MediaEvent.LocalCandidate -> {
                        candidates.getOrPut(name) { CopyOnWriteArrayList() } += e.candidate
                        if (peer != null) relay.deliver(peer, e.candidate)
                    }
                    is MediaEvent.RemoteVideo -> Unit
                }
            }
        }
    }

    private fun has(name: String, state: IceState) = iceStates[name]?.contains(state) == true

    private suspend fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            delay(50)
        }
        return cond()
    }

    /** A offers, B answers. [send] is what A may send; B asks to receive [peerReceive]. */
    private suspend fun connected(
        send: DataMode = DataMode.Balanced,
        peerReceive: DataMode = send,
        noise: NoiseSuppression = NoiseSuppression.Standard,
    ): Pair<WebRtcMediaSession, WebRtcMediaSession> {
        val a = session(noise)
        val b = session(noise)
        a.configure(emptyList(), IceTransportPolicy.All, video = false)
        b.configure(emptyList(), IceTransportPolicy.All, video = false)
        relay.register("A", a)
        relay.register("B", b)
        pump("A", a, peer = "B")
        pump("B", b, peer = "A")
        val p = AudioProfile.forMode(send)
        val offer = a.makeOffer(p, iceRestart = false)
        val answer = b.acceptOffer(offer, send = p, receive = AudioProfile.forMode(peerReceive))
        relay.remoteDescriptionSet("B")
        a.acceptAnswer(answer, send = p)
        relay.remoteDescriptionSet("A")
        return a to b
    }

    @Test
    fun loopbackAudioConnects() = runBlocking {
        val start = System.currentTimeMillis()
        val (a, b) = connected()
        val ok = waitFor(5_000 - (System.currentTimeMillis() - start)) {
            (has("A", IceState.Connected) || has("A", IceState.Completed)) &&
                (has("B", IceState.Connected) || has("B", IceState.Completed))
        }
        a.close()
        b.close()
        assertTrue(ok, "both sides must connect within 5 s; A=${iceStates["A"]} B=${iceStates["B"]}")
    }

    @Test
    fun localOfferIsMunged() = runBlocking {
        val a = session()
        a.configure(emptyList(), IceTransportPolicy.All, video = false)
        val offer = a.makeOffer(AudioProfile.forMode(DataMode.UltraLow), iceRestart = false)
        a.close()
        assertTrue("a=ptime:60" in offer)
        assertTrue("maxaveragebitrate=10000" in offer)
        assertTrue("a=fingerprint:sha-256" in offer)
        assertTrue("UDP/TLS/RTP/SAVPF" in offer)
    }

    @Test
    fun relayPolicyGathersOnlyRelay() = runBlocking {
        val a = session()
        a.configure(emptyList(), IceTransportPolicy.Relay, video = false)
        pump("A", a, peer = null)
        a.makeOffer(AudioProfile.forMode(DataMode.Balanced), iceRestart = false)
        delay(3_000)
        a.close()
        val types = candidates["A"].orEmpty().map { typ(it.sdp) }
        assertTrue("host" !in types && "srflx" !in types, "RELAY emitted $types")
        // Positive control: the same flow with All gathers host candidates, so silence above means the filter works.
        val c = session()
        c.configure(emptyList(), IceTransportPolicy.All, video = false)
        pump("C", c, peer = null)
        c.makeOffer(AudioProfile.forMode(DataMode.Balanced), iceRestart = false)
        val gathered = waitFor(3_000) { candidates["C"].orEmpty().any { typ(it.sdp) == "host" } }
        c.close()
        assertTrue(gathered, "ALL must gather host candidates")
    }

    @Test
    fun enhancedRunsRnnoise() = runBlocking {
        val before = CaptureHook.denoisedFrames
        val (a, b) = connected(noise = NoiseSuppression.Enhanced)
        assertTrue(CaptureHook.enabled)
        val ran = waitFor(10_000) { CaptureHook.denoisedFrames - before >= 50 }
        val frameSize = CaptureHook.lastFrameSize
        a.close()
        b.close()
        assertTrue(ran, "RNNoise denoised ${CaptureHook.denoisedFrames - before} frames; last frame $frameSize samples")
        assertEquals(RNNOISE_FRAME_SAMPLES, frameSize)
    }

    @Test
    fun standardDoesNotRunRnnoise() = runBlocking {
        val (a, b) = connected(noise = NoiseSuppression.Standard)
        // Let capture start, then check that the hook sees frames but RNNoise stays off.
        waitFor(10_000) { CaptureHook.lastFrameSize != 0 }
        val before = CaptureHook.denoisedFrames
        delay(1_000)
        val after = CaptureHook.denoisedFrames
        a.close()
        b.close()
        assertEquals(before, after)
    }

    /**
     * With the tone playing, an encoder that ignored the profile would send 30 kbps or more (Opus default). B asks
     * to receive High, so its answer does not carry the 10 kbps cap: the cap on A can only come from the munge A
     * applies to the remote answer (`send` UltraLow). The encoder rate is the `outbound-rtp` payload (`bytesSent`
     * there has no RTP headers); the transport counter also holds headers, RTCP and feedback, so it is checked
     * against the profile's wire budget instead. The tone (not the emulator's silent microphone) keeps DTX from
     * passing the test with nothing sent.
     */
    @Test
    fun sendBitrateStaysWithinTheProfile() = runBlocking {
        CaptureHook.tone = true
        val (a, b) = connected(send = DataMode.UltraLow, peerReceive = DataMode.High)
        assertTrue(waitFor(5_000) { has("A", IceState.Connected) || has("A", IceState.Completed) }, "the call must connect")
        a.setAudioMaxBitrate(50_000)
        val capped = a.audioMaxBitrateForTesting
        val s0 = a.stats()
        val r0 = a.rawStatsForTesting()
        val f0 = CaptureHook.toneFrames
        delay(5_000)
        val s1 = a.stats()
        val r1 = a.rawStatsForTesting()
        val toneFrames = CaptureHook.toneFrames - f0
        a.close()
        b.close()
        val codecBps = (audioOut(r1, "bytesSent") - audioOut(r0, "bytesSent")) * 8 / 5
        val wireBps = (s1.bytesSent - s0.bytesSent) * 8.0 / 5
        val target = r1.firstOrNull { it.type == "outbound-rtp" && it.strings["kind"] == "audio" }?.numbers?.get("targetBitrate")
        val profile = AudioProfile.forMode(DataMode.UltraLow)
        assertTrue(toneFrames >= 400, "the tone must play (one frame per 10 ms); it played $toneFrames frames")
        assertEquals(profile.maxAverageBitrate, capped)
        assertTrue(codecBps > 2_000, "the tone must be sent; codec payload $codecBps bit/s")
        assertTrue(codecBps <= 14_000, "codec payload $codecBps bit/s")
        if (target != null) assertTrue(target <= 10_000, "target $target bit/s")
        assertTrue(wireBps <= profile.wireBudget, "transport $wireBps bit/s over the wire budget ${profile.wireBudget}")
    }

    /**
     * The bandwidth estimate is capped at the mode's wire budget from the start of the call and after every mode change,
     * so WebRTC's padding and probes cannot grow past it under loss. Without the cap the estimate starts at 300 kbps and
     * may grow to 1 Gbps. This loopback cannot drop packets, so the loss case is measured by the call lab.
     * WebRTC never lowers the estimate below the negotiated Opus rate plus headers (the audio stream's allocation floor),
     * so a mode change without renegotiation (High to Balanced) is checked on the ceiling it applies, not the estimate.
     */
    @Test
    fun bweCeilingFollowsTheMode() = runBlocking {
        val (a, b) = connected(send = DataMode.UltraLow)
        assertTrue(waitFor(5_000) { has("A", IceState.Connected) || has("A", IceState.Completed) }, "the call must connect")
        val seen = mutableListOf<String>()
        suspend fun estimateWithin(mode: DataMode) {
            val budget = AudioProfile.forMode(mode).wireBudget
            assertEquals(budget, a.bweMaxForTesting, "$mode")
            assertEquals(budget, b.bweMaxForTesting, "$mode")
            delay(2_000)
            val stats = a.stats()
            val est = stats.availableOutgoingBitrate
            assertEquals(budget.toDouble(), stats.bweCeiling, "the adaptor needs the ceiling in the sample")
            seen += "$mode: estimate $est, budget $budget"
            assertNotNull(est, "audio must use send-side bandwidth estimation")
            // WebRTC keeps the estimate at or above the audio allocation floor (Opus target plus per-packet headers),
            // which for Data saver at 40 ms packets is about 30.3k, just over its 30k budget: allow 2 %.
            assertTrue(est <= budget * 1.02, "$mode: estimate $est bit/s over the wire budget $budget; $seen")
        }
        estimateWithin(DataMode.UltraLow)
        // A lower Opus cap within the mode (network step-down) keeps the mode's ceiling.
        a.setAudioMaxBitrate(6_000)
        assertEquals(AudioProfile.forMode(DataMode.UltraLow).wireBudget, a.bweMaxForTesting)
        // Renegotiation keeps that lower Opus cap rather than the negotiated profile's.
        val ds = AudioProfile.forMode(DataMode.DataSaver)
        a.acceptAnswer(b.acceptOffer(a.makeOffer(ds, iceRestart = false), send = ds, receive = ds), send = ds)
        assertEquals(AudioProfile.forMode(DataMode.UltraLow).wireBudget, a.bweMaxForTesting)
        // Mode changes (the call machine raises the Opus cap, then renegotiates) apply the new budget on both sides.
        for (mode in listOf(DataMode.DataSaver, DataMode.High)) {
            val p = AudioProfile.forMode(mode)
            a.setAudioMaxBitrate(p.maxAverageBitrate)
            b.setAudioMaxBitrate(p.maxAverageBitrate)
            val offer = a.makeOffer(p, iceRestart = false)
            val answer = b.acceptOffer(offer, send = p, receive = p)
            a.acceptAnswer(answer, send = p)
            estimateWithin(mode)
        }
        // High to Balanced is not renegotiated: the Opus cap carries the mode, and the ceiling follows it.
        a.setAudioMaxBitrate(AudioProfile.forMode(DataMode.Balanced).maxAverageBitrate)
        assertEquals(AudioProfile.forMode(DataMode.Balanced).wireBudget, a.bweMaxForTesting)
        a.close()
        b.close()
    }

    /** A configure that fails after the audio source is made disposes it (and the peer connection). */
    @Test
    fun failedConfigureDisposesTheAudioSource() = runBlocking {
        val a = session()
        a.beforeAudioTransceiverForTesting = { throw IllegalStateException("injected") }
        assertFailsWith<IllegalStateException> { a.configure(emptyList(), IceTransportPolicy.All, video = false) }
        val source = assertNotNull(a.audioSourceForTesting)
        // MediaSource.state() throws once the source is disposed.
        assertFailsWith<IllegalStateException> { source.state() }
        a.close()
    }

    @Test
    fun rollbackRefusesTheAbandonedAnswer() = runBlocking {
        val a = session()
        val b = session()
        a.configure(emptyList(), IceTransportPolicy.All, video = false)
        b.configure(emptyList(), IceTransportPolicy.All, video = false)
        val p = AudioProfile.forMode(DataMode.Balanced)
        val first = a.makeOffer(p, iceRestart = false)
        val answer = b.acceptOffer(first, send = p, receive = p)
        a.rollback()
        assertFailsWith<MediaSessionException> { a.acceptAnswer(answer, send = p) }
        val restart = a.makeOffer(p, iceRestart = true)
        a.close()
        b.close()
        assertNotEquals(ufrag(first), ufrag(restart))
    }

    private fun audioOut(stats: List<RawStat>, key: String): Double =
        stats.filter { it.type == "outbound-rtp" && it.strings["kind"] == "audio" }.sumOf { it.numbers[key] ?: 0.0 }

    private fun typ(sdp: String): String? = sdp.split(" ").let { parts -> parts.indexOf("typ").takeIf { it >= 0 }?.let { parts.getOrNull(it + 1) } }

    private fun ufrag(sdp: String): String? = sdp.lines().firstOrNull { it.startsWith("a=ice-ufrag:") }

    /** Holds each side's candidates until it has its remote description (adding one before that fails). */
    private class CandidateRelay {
        private val mutex = Mutex()
        private val ready = HashSet<String>()
        private val pending = HashMap<String, MutableList<IceCandidate>>()
        private val sessions = ConcurrentHashMap<String, WebRtcMediaSession>()

        fun register(name: String, s: WebRtcMediaSession) {
            sessions[name] = s
        }

        suspend fun deliver(target: String, c: IceCandidate) {
            val now = mutex.withLock {
                if (target in ready) true else {
                    pending.getOrPut(target) { mutableListOf() } += c
                    false
                }
            }
            if (now) runCatching { sessions[target]?.add(c) }
        }

        suspend fun remoteDescriptionSet(target: String) {
            val queued = mutex.withLock {
                ready += target
                pending.remove(target).orEmpty()
            }
            for (c in queued) runCatching { sessions[target]?.add(c) }
        }
    }
}
