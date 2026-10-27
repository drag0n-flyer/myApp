package org.example.app.ui.call

import androidx.lifecycle.ViewModel
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow
import org.example.app.core.calls.CallEndReason
import org.example.app.core.calls.CallEngine
import org.example.app.core.calls.CallPhase
import org.example.app.core.calls.CallState
import org.example.app.core.calls.DataModePolicy
import org.example.app.core.calls.DataSheetOption
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.SimulatedCallEngine
import org.example.app.core.calls.SimulatedNetwork
import org.example.app.core.chats.CallSummary
import org.example.app.core.chats.ChatStore
import org.example.app.core.settings.CallSettings
import org.example.app.core.settings.ServerPolicy

/**
 * Drives the call screens from a [CallEngine]. [simulator] is the same engine when it is the sample one; it supplies
 * the one-second clock (only while a call screen is visible) and, if [showSimulator], the network menu.
 */
class CallViewModel(
    private val engine: CallEngine,
    private val store: ChatStore,
    private val server: ServerPolicy,
    private val settings: CallSettings,
    private val simulator: SimulatedCallEngine? = null,
    val showSimulator: Boolean = false,
) : ViewModel() {
    val call: StateFlow<CallState?> = engine.state

    private var outgoing = true
    private var recorded = false

    fun contactName(call: CallState): String = store.contact(call.contactId)?.displayName ?: call.contactId

    /** "Auto · Data saver" resolves from these two keys: the preference text key and the mode in use. */
    fun isAuto(call: CallState): Boolean = (call.inCallPreference ?: settings.callDataMode) == ModePreference.Auto

    fun sheetOptions(call: CallState): List<DataSheetOption> =
        DataModePolicy.sheetOptions(server, settings, call.network, call.inCallPreference)

    fun start(contactId: String, video: Boolean) {
        outgoing = true
        recorded = false
        engine.start(contactId, video)
    }

    fun answer(video: Boolean) {
        outgoing = false
        recorded = false
        engine.answer(video)
    }

    fun decline() {
        outgoing = false
        recorded = false
        engine.decline()
        recordIfEnded()
    }

    fun hangUp() {
        engine.hangUp()
        recordIfEnded()
    }

    fun toggleMute() = call.value?.let { engine.setMuted(!it.isMuted) }
    fun toggleSpeaker() = call.value?.let { engine.setSpeaker(!it.isSpeakerOn) }
    fun toggleVideo() = call.value?.let { engine.setVideo(!it.isVideoOn) }
    fun setMode(preference: ModePreference) = engine.setMode(preference)

    /** One second of the sample clock. Does nothing with a real engine, which keeps its own time. */
    fun tick() {
        simulator?.advance(1.0)
        recordIfEnded()
    }

    fun simulate(network: SimulatedNetwork) {
        simulator?.simulate(network)
    }

    fun advance(seconds: Double) {
        simulator?.advance(seconds)
        recordIfEnded()
    }

    /** Forgets a finished call so the app returns to where it was. */
    fun dismissEnded() {
        if (call.value?.isEnded == true) engine.reset()
    }

    private fun recordIfEnded() {
        val state = call.value ?: return
        val ended = state.phase as? CallPhase.Ended ?: return
        if (recorded) return
        recorded = true
        val video = state.isVideoCall
        val summary = when {
            ended.reason == CallEndReason.Declined || ended.reason == CallEndReason.Missed ->
                if (video) CallSummary.MissedVideo else CallSummary.MissedAudio
            state.elapsed <= 0.0 && !outgoing -> if (video) CallSummary.MissedVideo else CallSummary.MissedAudio
            video -> CallSummary.Video(state.elapsed)
            else -> CallSummary.Audio(state.elapsed)
        }
        store.recordCall(summary, state.contactId, outgoing, Instant.now())
    }
}
