package org.example.app.calls.android.media

import android.content.Context
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.example.app.calls.policy.AudioProfile
import org.example.app.calls.policy.IceTransportPolicy
import org.example.app.calls.policy.MediaStats
import org.example.app.calls.policy.SdpMunger
import org.example.app.calls.policy.VideoLadder
import org.example.app.calls.policy.VideoTier
import org.example.app.calls.session.IceServer
import org.example.app.calls.session.MediaEvent
import org.example.app.calls.session.MediaSession
import org.example.app.contracts.DataMode
import org.example.app.contracts.IceCandidate
import org.example.app.core.settings.NoiseSuppression
import org.example.app.core.settings.UserSettings
import org.webrtc.AddIceObserver
import org.webrtc.AudioCapturePostProcessing
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/** RNNoise's rate: 480 samples are 10 ms. */
const val RNNOISE_SAMPLE_RATE_HZ = 48_000

class MediaSessionException(message: String) : Exception(message)

/**
 * The process-wide [PeerConnectionFactory] (design §8.1, §8.2): the Opus PLC field trial, WebRTC's own AEC3 and
 * noise suppression with the hardware ones off, RNNoise in the capture hook ([CaptureHook]), hardware H.264 with
 * software VP8 as the fallback.
 */
object WebRtcFactory {
    private var instance: PeerConnectionFactory? = null
    private var egl: EglBase? = null

    @Synchronized
    fun shared(context: Context): PeerConnectionFactory = instance ?: run {
        val app = context.applicationContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app)
                .setFieldTrials(AudioSetup.FIELD_TRIALS)
                .createInitializationOptions(),
        )
        val adm = JavaAudioDeviceModule.builder(app)
            // RNNoise needs 48 kHz capture (480-sample frames); some devices and emulators default to 16 kHz.
            .setInputSampleRate(RNNOISE_SAMPLE_RATE_HZ)
            .setUseHardwareAcousticEchoCanceler(AudioSetup.USE_HARDWARE_ACOUSTIC_ECHO_CANCELER)
            .setUseHardwareNoiseSuppressor(AudioSetup.USE_HARDWARE_NOISE_SUPPRESSOR)
            .createAudioDeviceModule()
        val eglBase = EglBase.create().also { egl = it }
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setAudioProcessingFactory(AudioCapturePostProcessing(CaptureHook.processor()))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, false))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
            .also { instance = it }
    }

    internal fun eglContext(): EglBase.Context? = egl?.eglBaseContext
}

/**
 * [MediaSession] over WebRTC: Unified Plan, one BUNDLE, Opus audio, optional video. The Android form of the
 * iPhone's `WebRTCMediaSession` (CallMedia). [settings] gives the noise setting when the call starts.
 */
class WebRtcMediaSession(context: Context, private val settings: UserSettings) : MediaSession {
    private val appContext = context.applicationContext
    private val channel = Channel<MediaEvent>(Channel.UNLIMITED)
    override val events: Flow<MediaEvent> = channel.receiveAsFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    // Behind `lock`.
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var audioSender: RtpSender? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoSender: RtpSender? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var capturing: VideoTier? = null
    private var sendProfile: AudioProfile? = null
    private var bweAudioBitrate: Int? = null // the Opus cap the estimator ceiling is derived from; null until known
    private var requestedAudioBitrate: Int? = null // the last setAudioMaxBitrate request; renegotiation keeps it
    private var bweVideoBitrate = 0 // the video sender's cap while video is on
    private var bweMax: Int? = null // the ceiling last applied
    private var closed = false
    private val aggregator = MediaStatsAggregator()

    /** Test hooks: the last audio source made, and a step that runs just before the audio transceiver is added. */
    internal var audioSourceForTesting: AudioSource? = null
    internal var beforeAudioTransceiverForTesting: (() -> Unit)? = null

    /** Test hook: `maxBitrateBps` of the audio sender's first encoding. */
    internal val audioMaxBitrateForTesting: Int?
        get() = synchronized(lock) { audioSender }?.parameters?.encodings?.firstOrNull()?.maxBitrateBps

    /** Test hook: the bandwidth estimator ceiling last applied with `setBitrate`. */
    internal val bweMaxForTesting: Int?
        get() = synchronized(lock) { bweMax }

    /** Test hook: the flattened stats report (the `outbound-rtp` payload counters are not in [MediaStats]). */
    internal suspend fun rawStatsForTesting(): List<RawStat> = rawStats()

    override suspend fun configure(iceServers: List<IceServer>, policy: IceTransportPolicy, video: Boolean) {
        synchronized(lock) {
            if (closed) throw MediaSessionException("closed")
            if (pc != null) throw MediaSessionException("already configured")
        }
        val f = WebRtcFactory.shared(appContext)
        val config = PeerConnection.RTCConfiguration(
            iceServers.map {
                PeerConnection.IceServer.builder(it.urls).setUsername(it.username).setPassword(it.credential).createIceServer()
            },
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = when (policy) {
                IceTransportPolicy.Relay -> PeerConnection.IceTransportsType.RELAY
                IceTransportPolicy.All -> PeerConnection.IceTransportsType.ALL
            }
        }
        val peer = f.createPeerConnection(config, Observer()) ?: throw MediaSessionException("no peer connection")
        try {
            attach(f, peer, video)
        } catch (e: Exception) {
            peer.dispose()
            throw e
        }
        if (video) startRemoteVideoMonitor()
    }

    private fun attach(f: PeerConnectionFactory, peer: PeerConnection, video: Boolean) {
        val noise = settings.noiseSuppression
        CaptureHook.enabled = AudioSetup.processing(noise).rnnoise
        CaptureHook.resetForCall()
        val aSource = f.createAudioSource(audioConstraints(noise)).also { audioSourceForTesting = it }
        var aTrack: AudioTrack? = null
        var aSender: RtpSender? = null
        var vSource: VideoSource? = null
        var vTrack: VideoTrack? = null
        var vSender: RtpSender? = null
        try {
            aTrack = f.createAudioTrack("audio0", aSource)
            beforeAudioTransceiverForTesting?.invoke()
            aSender = peer.addTransceiver(aTrack, transceiverInit()).sender
            if (video) {
                vSource = f.createVideoSource(false)
                vTrack = f.createVideoTrack("video0", vSource).apply { setEnabled(false) } // off until setVideo(tier)
                val transceiver = peer.addTransceiver(vTrack, transceiverInit())
                vSender = transceiver.sender
                preferH264(f, transceiver)
            }
            synchronized(lock) {
                if (closed) throw MediaSessionException("closed while configuring")
                pc = peer
                audioSource = aSource
                audioTrack = aTrack
                audioSender = aSender
                videoSource = vSource
                videoTrack = vTrack
                videoSender = vSender
            }
        } catch (e: Exception) {
            // A track a transceiver took is disposed with the peer connection (the caller disposes it); the rest here.
            if (aSender == null) aTrack?.dispose()
            if (vSender == null) vTrack?.dispose()
            vSource?.dispose()
            aSource.dispose()
            throw e
        }
    }

    override suspend fun makeOffer(receive: AudioProfile, iceRestart: Boolean): String {
        val peer = requirePc()
        val constraints = MediaConstraints().apply {
            if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        val offer = create(peer, offer = true, constraints)
        val munged = SdpMunger.apply(receive, offer)
        setLocal(peer, SessionDescription(SessionDescription.Type.OFFER, munged))
        return munged
    }

    override suspend fun acceptOffer(sdp: String, send: AudioProfile, receive: AudioProfile): String {
        val peer = requirePc()
        // The remote offer's Opus parameters describe what we send; ours go in the answer.
        setRemote(peer, SessionDescription(SessionDescription.Type.OFFER, SdpMunger.apply(send, sdp)))
        applySendProfile(send)
        val answer = create(peer, offer = false, MediaConstraints())
        val munged = SdpMunger.apply(receive, answer)
        setLocal(peer, SessionDescription(SessionDescription.Type.ANSWER, munged))
        return munged
    }

    override suspend fun acceptAnswer(sdp: String, send: AudioProfile) {
        val peer = requirePc()
        setRemote(peer, SessionDescription(SessionDescription.Type.ANSWER, SdpMunger.apply(send, sdp)))
        applySendProfile(send)
    }

    override suspend fun rollback() {
        setLocal(requirePc(), SessionDescription(SessionDescription.Type.ROLLBACK, ""))
    }

    override suspend fun add(candidate: IceCandidate) {
        val peer = requirePc()
        val c = org.webrtc.IceCandidate(candidate.sdpMid ?: "", candidate.sdpMLineIndex, candidate.sdp)
        suspendCancellableCoroutine { k ->
            peer.addIceCandidate(c, object : AddIceObserver {
                override fun onAddSuccess() = k.resume(Unit)
                override fun onAddFailure(error: String) = k.resumeWithException(MediaSessionException("add candidate: $error"))
            })
        }
    }

    override suspend fun setAudioMaxBitrate(bps: Int) {
        val (sender, profile) = synchronized(lock) {
            requestedAudioBitrate = bps
            audioSender to sendProfile
        }
        sender ?: return
        val cap = MediaLogic.senderBitrateCap(bps, profile)
        val params = sender.parameters
        params.encodings.forEach { it.maxBitrateBps = cap }
        sender.setParameters(params)
        applyBweCeiling(audioBitrate = cap)
    }

    override suspend fun setVideo(tier: VideoTier?, ceiling: DataMode) {
        val effective = VideoLadder.effectiveTier(tier, ceiling)
        synchronized(lock) {
            val track = videoTrack ?: return
            val sender = videoSender ?: return
            if (effective == null) {
                track.setEnabled(false)
                stopCapture()
                bweVideoBitrate = 0
                return@synchronized
            }
            val spec = VideoLadder.spec(effective)
            val params = sender.parameters
            params.encodings.forEach {
                it.maxBitrateBps = spec.maxBps
                it.maxFramerate = spec.fps
            }
            sender.setParameters(params)
            if (capturing != effective) startCapture(effective)
            track.setEnabled(true)
            bweVideoBitrate = spec.maxBps
        }
        applyBweCeiling()
    }

    override suspend fun setMuted(on: Boolean) {
        synchronized(lock) { audioTrack }?.setEnabled(!on)
    }

    /** RNNoise follows the setting at once; WebRTC's own noise suppression is set when the call starts. */
    override suspend fun setNoiseSuppression(mode: NoiseSuppression) {
        CaptureHook.enabled = AudioSetup.processing(mode).rnnoise
    }

    override suspend fun stats(): MediaStats {
        val raw = rawStats()
        // The adaptor reads an estimate at the ceiling as capped.
        return synchronized(lock) { aggregator.aggregate(raw).copy(bweCeiling = bweMax?.toDouble()) }
    }

    override suspend fun close() {
        val peer: PeerConnection?
        synchronized(lock) {
            if (closed) return
            closed = true
            stopCapture()
            peer = pc
            pc = null
        }
        scope.cancel()
        peer?.dispose() // closes the connection and disposes its transceivers and tracks
        synchronized(lock) {
            videoSource?.dispose()
            audioSource?.dispose()
            textureHelper?.dispose()
            audioTrack = null; audioSender = null; audioSource = null
            videoTrack = null; videoSender = null; videoSource = null; textureHelper = null
        }
        channel.close()
    }

    // Internals

    /** Records the negotiated send profile and re-applies the ceiling with the current Opus cap (a lower request survives renegotiation). */
    private fun applySendProfile(send: AudioProfile) {
        val requested = synchronized(lock) {
            sendProfile = send
            requestedAudioBitrate
        }
        applyBweCeiling(audioBitrate = MediaLogic.senderBitrateCap(requested ?: send.maxAverageBitrate, send))
    }

    /**
     * Caps WebRTC's bandwidth estimate (and with it the pacer's padding and the probes) at the mode's wire budget, plus
     * the video cap while video is on. Applied whenever the send profile, the Opus cap or the video tier changes.
     */
    private fun applyBweCeiling(audioBitrate: Int? = null) {
        val (peer, max) = synchronized(lock) {
            if (audioBitrate != null) bweAudioBitrate = audioBitrate
            val audio = bweAudioBitrate ?: return
            val max = MediaLogic.bweMaxBitrate(audio, bweVideoBitrate)
            if (max == bweMax) return
            bweMax = max
            pc to max
        }
        peer?.setBitrate(null, null, max)
    }

    private fun requirePc(): PeerConnection = synchronized(lock) { pc } ?: throw MediaSessionException("not configured")

    private fun transceiverInit() =
        RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("call"))

    private fun audioConstraints(noise: NoiseSuppression): MediaConstraints {
        val p = AudioSetup.processing(noise)
        return MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", p.echoCancellation.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", p.webRtcNoiseSuppression.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
    }

    /** H.264 constrained baseline first (design §8.2); a codec list WebRTC refuses leaves its default order. */
    private fun preferH264(f: PeerConnectionFactory, transceiver: RtpTransceiver) {
        val codecs = f.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        transceiver.setCodecPreferences(VideoCodecOrder.order(codecs) { VideoCodec(it.name, it.parameters ?: emptyMap()) })
    }

    // Behind `lock`.
    private fun startCapture(tier: VideoTier) {
        val source = videoSource ?: return
        val spec = VideoLadder.spec(tier)
        val cam = capturer ?: run {
            val enumerator = Camera2Enumerator(appContext)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull() ?: return
            val helper = SurfaceTextureHelper.create("CallCamera", WebRtcFactory.eglContext())
            enumerator.createCapturer(name, null).also {
                it.initialize(helper, appContext, source.capturerObserver)
                textureHelper = helper
                capturer = it
            }
        }
        if (capturing == null) cam.startCapture(spec.width, spec.height, spec.fps) else cam.changeCaptureFormat(spec.width, spec.height, spec.fps)
        capturing = tier
    }

    // Behind `lock`.
    private fun stopCapture() {
        if (capturing != null) {
            try {
                capturer?.stopCapture()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            capturing = null
        }
        if (closed) {
            capturer?.dispose()
            capturer = null
        }
    }

    private suspend fun rawStats(): List<RawStat> {
        val peer = synchronized(lock) { pc } ?: return emptyList()
        val report: RTCStatsReport = suspendCancellableCoroutine { k -> peer.getStats { k.resume(it) } }
        return report.statsMap.values.map { StatsMapper.flatten(it.id, it.type, it.members) }
    }

    /** Polls the inbound video frame counter and emits [MediaEvent.RemoteVideo] when the peer starts or stops sending. */
    private fun startRemoteVideoMonitor() {
        scope.launch {
            val detector = RemoteVideoDetector()
            while (isActive) {
                delay(1_000)
                val frames = rawStats().firstOrNull { it.type == "inbound-rtp" && it.strings["kind"] == "video" }?.numbers?.get("framesDecoded")
                detector.sample(frames)?.let { channel.trySend(MediaEvent.RemoteVideo(it)) }
            }
        }
    }

    private suspend fun create(peer: PeerConnection, offer: Boolean, constraints: MediaConstraints): String =
        suspendCancellableCoroutine { k ->
            val observer = object : SdpObserverAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription) = k.resume(sdp.description)
                override fun onCreateFailure(error: String) = k.resumeWithException(MediaSessionException("create: $error"))
            }
            if (offer) peer.createOffer(observer, constraints) else peer.createAnswer(observer, constraints)
        }

    private suspend fun setLocal(peer: PeerConnection, d: SessionDescription) = suspendCancellableCoroutine { k ->
        peer.setLocalDescription(setObserver(k), d)
    }

    private suspend fun setRemote(peer: PeerConnection, d: SessionDescription) = suspendCancellableCoroutine { k ->
        peer.setRemoteDescription(setObserver(k), d)
    }

    private fun setObserver(k: kotlinx.coroutines.CancellableContinuation<Unit>) = object : SdpObserverAdapter() {
        override fun onSetSuccess() = k.resume(Unit)
        override fun onSetFailure(error: String) = k.resumeWithException(MediaSessionException("set description: $error"))
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            MediaLogic.iceState(state.name)?.let { channel.trySend(MediaEvent.IceStateChanged(it)) }
        }

        override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
            channel.trySend(MediaEvent.LocalCandidate(IceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)))
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }
}
