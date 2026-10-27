package org.example.app.calls.session

import org.example.app.calls.policy.AudioProfile
import org.example.app.calls.policy.IceTransportPolicy
import org.example.app.calls.policy.VideoTier
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.DataMode
import org.example.app.contracts.IceCandidate
import org.example.app.core.calls.CallEndReason
import org.example.app.core.calls.CallState
import org.example.app.core.chats.CallSummary
import org.example.app.core.settings.NoiseSuppression

/** Client call frame types (server design §7.3). */
object CallFrame {
    const val INVITE = "call.invite"
    const val ANSWER = "call.answer"
    const val SIGNAL = "call.signal"
    const val DECLINE = "call.decline"
    const val HANGUP = "call.hangup"
}

/** What [CallMachine] asks the engine to do, in order. Mirrors the iPhone's `CallEffect`. */
sealed interface CallEffect {
    /** Send a client frame. `payload` is sealed by the engine (`call.invite`, `call.answer`, `call.signal`); decline and hangup carry none. */
    data class SendFrame(val type: String, val payload: CallSignalPayload? = null) : CallEffect

    /** Fetch ICE servers, then set up the peer connection. */
    data class ConfigureMedia(val policy: IceTransportPolicy, val video: Boolean) : CallEffect

    /** Make a local offer; the engine replies with [CallEvent.LocalOffer]. */
    sealed interface MakeOffer : CallEffect {
        val receive: AudioProfile
    }

    /** A first offer, or a renegotiation for a mode change. */
    data class CreateOffer(override val receive: AudioProfile) : MakeOffer

    /** An offer with an ICE restart. */
    data class RestartIce(override val receive: AudioProfile) : MakeOffer

    /** `setRemoteDescription`. Only SDP that passed the fingerprint check gets here. */
    sealed interface SetRemote : CallEffect {
        val sdp: String
    }

    /** Set the remote offer and make an answer; the engine replies with [CallEvent.LocalAnswer]. */
    data class AcceptOffer(override val sdp: String, val send: AudioProfile, val receive: AudioProfile) : SetRemote

    data class AcceptAnswer(override val sdp: String, val send: AudioProfile) : SetRemote

    data object Rollback : CallEffect
    data class AddCandidate(val candidate: IceCandidate) : CallEffect
    data class SetAudioMaxBitrate(val bps: Int) : CallEffect

    /** `ceiling` is the call's ceiling (own setting and the peer's, whichever is lower); the media layer clamps the tier to it. */
    data class SetVideo(val tier: VideoTier?, val ceiling: DataMode) : CallEffect

    data class SetMuted(val on: Boolean) : CallEffect
    data class SetNoiseSuppression(val mode: NoiseSuppression) : CallEffect

    /** The call is over: close the media. */
    data class End(val reason: CallEndReason) : CallEffect

    data class RecordHistory(val summary: CallSummary, val outgoing: Boolean) : CallEffect
    data class Publish(val state: CallState?) : CallEffect
}
