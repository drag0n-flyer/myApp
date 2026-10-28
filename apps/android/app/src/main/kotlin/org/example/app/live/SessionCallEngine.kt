package org.example.app.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import org.example.app.core.calls.CallEngine
import org.example.app.core.calls.CallState
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType

/**
 * The live build's [CallEngine]: the screens hold this one object for the app's life, and it forwards to the open
 * session's `LiveCallEngine` ([current]). With no session there is no call and every action does nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionCallEngine(scope: CoroutineScope) : CallEngine {
    private val engine = MutableStateFlow<CallEngine?>(null)

    val current: CallEngine? get() = engine.value

    fun use(next: CallEngine?) {
        engine.value = next
    }

    override val state: StateFlow<CallState?> =
        engine.flatMapLatest { it?.state ?: flowOf(null) }.stateIn(scope, SharingStarted.Eagerly, null)

    override fun start(contactId: String, video: Boolean) { current?.start(contactId, video) }
    override fun answer(video: Boolean) { current?.answer(video) }
    override fun decline() { current?.decline() }
    override fun hangUp() { current?.hangUp() }
    override fun setMode(preference: ModePreference) { current?.setMode(preference) }
    override fun setMuted(on: Boolean) { current?.setMuted(on) }
    override fun setSpeaker(on: Boolean) { current?.setSpeaker(on) }
    override fun setVideo(on: Boolean) { current?.setVideo(on) }
    override fun setNetwork(type: NetworkType) { current?.setNetwork(type) }
    override fun reset() { current?.reset() }
}
