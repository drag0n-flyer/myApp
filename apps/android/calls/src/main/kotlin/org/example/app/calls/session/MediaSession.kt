package org.example.app.calls.session

import kotlinx.coroutines.flow.Flow
import org.example.app.calls.policy.AudioProfile
import org.example.app.calls.policy.IceTransportPolicy
import org.example.app.calls.policy.MediaStats
import org.example.app.calls.policy.VideoTier
import org.example.app.contracts.DataMode
import org.example.app.contracts.IceCandidate
import org.example.app.core.settings.NoiseSuppression

/** A STUN or TURN server from `GET /v1/turn` (server design §7.4). */
data class IceServer(val urls: List<String>, val username: String, val credential: String)

/**
 * One call's peer connection. Mirrors the iPhone's `MediaSession` (CallCore). The engine runs [CallEffect]s on
 * it; only SDP that passed the fingerprint check reaches [acceptOffer] or [acceptAnswer]. Task E5 provides the
 * WebRTC implementation.
 */
interface MediaSession {
    val events: Flow<MediaEvent>
    suspend fun configure(iceServers: List<IceServer>, policy: IceTransportPolicy, video: Boolean)
    suspend fun makeOffer(receive: AudioProfile, iceRestart: Boolean): String
    suspend fun acceptOffer(sdp: String, send: AudioProfile, receive: AudioProfile): String
    suspend fun acceptAnswer(sdp: String, send: AudioProfile)
    suspend fun rollback()
    suspend fun add(candidate: IceCandidate)
    suspend fun setAudioMaxBitrate(bps: Int)

    /** `ceiling` is the call's data-mode ceiling (own setting and the peer's, whichever is lower); the tier must be clamped to it. */
    suspend fun setVideo(tier: VideoTier?, ceiling: DataMode)
    suspend fun setMuted(on: Boolean)
    suspend fun setNoiseSuppression(mode: NoiseSuppression)
    suspend fun stats(): MediaStats
    suspend fun close()
}

/** Makes a fresh [MediaSession] for each call. */
fun interface MediaSessionFactory {
    fun create(): MediaSession
}
