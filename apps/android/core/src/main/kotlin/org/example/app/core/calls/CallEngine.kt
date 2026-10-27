package org.example.app.core.calls

import kotlinx.coroutines.flow.StateFlow

enum class CallEndReason { HungUp, ConnectionLost, Declined, Missed, AnsweredElsewhere, Failed }

sealed interface CallPhase {
    data object Connecting : CallPhase
    data object Active : CallPhase
    data object Reconnecting : CallPhase
    /** Outgoing call, waiting for the other person to answer. */
    data object Ringing : CallPhase
    /** A ringing call shown in the app. */
    data object Incoming : CallPhase
    data class Ended(val reason: CallEndReason) : CallPhase
}

enum class CallQuality(val titleKey: String) {
    Good("call_quality_good"),
    Weak("call_quality_weak"),
}

/** Explains what the app did when the network changed. The UI resolves `textKey` from string resources. */
enum class CallBanner(val textKey: String) {
    VideoLowered("call_banner_video_lowered"),
    AudioOnly("call_banner_audio_only"),
    Reconnecting("call_banner_reconnecting"),
    ConnectionLost("call_banner_connection_lost"),
}

data class CallState(
    val contactId: String,
    val phase: CallPhase,
    val isVideoCall: Boolean,
    val isVideoOn: Boolean,
    val effectiveMode: DataMode,
    val network: NetworkType,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val quality: CallQuality = CallQuality.Good,
    val banner: CallBanner? = null,
    val elapsed: Double = 0.0,
    val bytesUsed: Long = 0,
    val inCallPreference: ModePreference? = null,
) {
    val isEnded: Boolean get() = phase is CallPhase.Ended

    /** Whole seconds on the call clock; the UI formats it. */
    val elapsedSeconds: Long get() = elapsed.toLong()
}

/** Runs calls. `SimulatedCallEngine` now; the WebRTC engine after Phase 0. `state` is null when there is no call. */
interface CallEngine {
    val state: StateFlow<CallState?>
    fun start(contactId: String, video: Boolean)
    fun answer(video: Boolean)
    fun decline()
    fun hangUp()
    fun setMode(preference: ModePreference)
    fun setMuted(on: Boolean)
    fun setSpeaker(on: Boolean)
    fun setVideo(on: Boolean)
    fun setNetwork(type: NetworkType)
    /** Forgets a finished call. */
    fun reset()
}
