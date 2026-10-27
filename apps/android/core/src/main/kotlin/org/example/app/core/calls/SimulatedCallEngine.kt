package org.example.app.core.calls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.example.app.core.settings.CallSettings
import org.example.app.core.settings.ServerPolicy

enum class SimulatedNetwork { Good, Weak, VeryWeak, Lost }

/** A call engine that plays network conditions on demand, so call screens work without real calls. */
class SimulatedCallEngine(
    server: ServerPolicy,
    settings: CallSettings,
    network: NetworkType,
) : CallEngine {
    companion object {
        const val RECONNECT_TIMEOUT: Double = 30.0
    }

    private val _state = MutableStateFlow<CallState?>(null)
    override val state: StateFlow<CallState?> = _state.asStateFlow()

    var server: ServerPolicy = server
        set(value) { field = value; recompute() }
    var settings: CallSettings = settings
        set(value) { field = value; recompute() }
    private var network: NetworkType = network
    private var condition = SimulatedNetwork.Good
    private var reconnectingFor = 0.0

    @Synchronized
    override fun start(contactId: String, video: Boolean) {
        condition = SimulatedNetwork.Good
        reconnectingFor = 0.0
        val videoOn = video && !(network == NetworkType.Mobile && settings.videoOffOnMobile)
        _state.value = CallState(contactId, CallPhase.Connecting, video, videoOn, DataMode.UltraLow, network, isSpeakerOn = video)
        recompute()
    }

    /** Sample build: a call rings in the app. */
    @Synchronized
    fun simulateIncoming(contactId: String, video: Boolean) {
        if (_state.value?.isEnded == false) return
        condition = SimulatedNetwork.Good
        reconnectingFor = 0.0
        _state.value = CallState(contactId, CallPhase.Incoming, video, false, DataMode.UltraLow, network, isSpeakerOn = video)
        recompute()
    }

    @Synchronized
    override fun answer(video: Boolean) {
        if (_state.value?.phase != CallPhase.Incoming) return
        update { it.copy(phase = CallPhase.Active, isVideoOn = video && it.isVideoCall && !(network == NetworkType.Mobile && settings.videoOffOnMobile)) }
    }

    @Synchronized
    override fun decline() {
        if (_state.value?.phase != CallPhase.Incoming) return
        update { it.copy(phase = CallPhase.Ended(CallEndReason.Declined), banner = null) }
    }

    @Synchronized
    override fun hangUp() = update { it.copy(phase = CallPhase.Ended(CallEndReason.HungUp), banner = null) }

    @Synchronized
    override fun setMode(preference: ModePreference) = update { it.copy(inCallPreference = preference) }

    @Synchronized
    override fun setMuted(on: Boolean) = update { it.copy(isMuted = on) }

    @Synchronized
    override fun setSpeaker(on: Boolean) = update { it.copy(isSpeakerOn = on) }

    @Synchronized
    override fun setVideo(on: Boolean) = update { it.copy(isVideoOn = on && condition != SimulatedNetwork.VeryWeak) }

    @Synchronized
    override fun setNetwork(type: NetworkType) {
        network = type
        update { it.copy(network = type) }
    }

    @Synchronized
    override fun reset() { _state.value = null }

    @Synchronized
    fun simulate(newCondition: SimulatedNetwork) {
        if (_state.value?.isEnded != false) return
        condition = newCondition
        update { s ->
            when (newCondition) {
                SimulatedNetwork.Good -> {
                    reconnectingFor = 0.0
                    s.copy(phase = CallPhase.Active, quality = CallQuality.Good, banner = null)
                }
                SimulatedNetwork.Weak ->
                    s.copy(phase = CallPhase.Active, quality = CallQuality.Weak, banner = if (s.isVideoOn) CallBanner.VideoLowered else null)
                SimulatedNetwork.VeryWeak ->
                    s.copy(phase = CallPhase.Active, quality = CallQuality.Weak, isVideoOn = false, banner = CallBanner.AudioOnly)
                SimulatedNetwork.Lost ->
                    s.copy(phase = CallPhase.Reconnecting, quality = CallQuality.Weak, banner = CallBanner.Reconnecting)
            }
        }
    }

    /** Moves the simulated clock forward. */
    @Synchronized
    fun advance(seconds: Double) {
        if (_state.value?.isEnded != false) return
        update { s ->
            when (s.phase) {
                CallPhase.Connecting, CallPhase.Active ->
                    s.copy(phase = CallPhase.Active, elapsed = s.elapsed + seconds, bytesUsed = s.bytesUsed + (s.effectiveMode.bytesPerSecond * seconds).toLong())
                CallPhase.Reconnecting -> {
                    reconnectingFor += seconds
                    if (reconnectingFor >= RECONNECT_TIMEOUT) s.copy(phase = CallPhase.Ended(CallEndReason.ConnectionLost), banner = CallBanner.ConnectionLost) else s
                }
                CallPhase.Ringing, CallPhase.Incoming, is CallPhase.Ended -> s
            }
        }
    }

    // Private

    private val sustainable: DataMode
        get() = when (condition) {
            SimulatedNetwork.Good -> DataMode.High
            SimulatedNetwork.Weak -> DataMode.DataSaver
            SimulatedNetwork.VeryWeak, SimulatedNetwork.Lost -> DataMode.UltraLow
        }

    private fun update(change: (CallState) -> CallState) {
        // A finished call is frozen: later setting or network changes must not re-publish it.
        val current = _state.value ?: return
        if (current.isEnded) return
        val next = change(current)
        _state.value = next.copy(
            effectiveMode = DataModePolicy.effective(server, settings, network, next.inCallPreference, sustainable),
        )
    }

    private fun recompute() = update { it }
}
