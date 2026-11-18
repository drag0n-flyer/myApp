package org.example.app.ui.media

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the voice bubbles show. [messageId] is the message that is loading, playing or paused; null when nothing is. */
data class VoiceState(
    val messageId: String? = null,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    /** The message whose playback failed; its bubble says "Couldn't download" and a tap tries again. */
    val failedId: String? = null,
)

/** The sound side of [VoicePlayer], so the rules above it can be tested without a phone. Called on the main thread. */
interface VoiceEngine {
    /** Starts preparing [file]; exactly one of the [Listener] calls follows, on the main thread. */
    fun prepare(file: File, listener: Listener)
    fun start()
    fun pause()
    val positionMs: Int
    /** Frees the player. Safe to call twice. */
    fun release()

    interface Listener {
        fun onPrepared(durationMs: Int)
        fun onCompleted()
        fun onError()
    }
}

fun interface VoiceEngineFactory {
    fun create(): VoiceEngine
}

/**
 * Plays voice messages, one at a time, for one chat screen. Tap a message to play it, tap it again to pause and again to
 * go on; tapping another stops the first. A message is decrypted for as long as it plays and its plaintext is deleted
 * the moment it stops, ends or fails. Anything that arrives late (an open that finished after another tap, a callback of
 * a player that was stopped) is dropped. [release] when the screen leaves: it stops everything and the player takes no
 * more taps. Main thread only; [scope] should be the screen's own, [io] is where the decrypt runs.
 */
class VoicePlayer(
    private val scope: CoroutineScope,
    private val opener: AttachmentOpener?,
    private val engines: VoiceEngineFactory,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val tickMs: Long = 100,
) {
    private class Active(val opened: OpenedMedia, val engine: VoiceEngine) {
        var prepared = false
    }

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state

    // Bumped whenever what is playing changes, so a late callback or a late open knows it is out of date.
    private var token = 0
    private var loading: Job? = null
    private var ticker: Job? = null
    private var active: Active? = null
    private var released = false

    fun toggle(messageId: String) {
        if (released) return
        if (_state.value.messageId == messageId) {
            val a = active
            when {
                // Still loading: a second tap cancels the load.
                a == null || !a.prepared -> stop()
                _state.value.playing -> pause(a)
                else -> resume(a)
            }
            return
        }
        begin(messageId)
    }

    /** Stops what plays, frees the player and deletes the plaintext. */
    fun stop() {
        teardown()
        _state.value = VoiceState()
    }

    /** [stop], and the player is finished: later taps do nothing. */
    fun release() {
        released = true
        stop()
    }

    private fun begin(messageId: String) {
        teardown()
        val mine = token
        _state.value = VoiceState(messageId = messageId)
        loading = scope.launch {
            val opened = opener?.openOrNull(messageId, io)
            if (mine != token) {
                opened?.close()
                return@launch
            }
            if (opened == null) {
                fail(messageId)
                return@launch
            }
            val engine = engines.create()
            val a = Active(opened, engine)
            active = a
            engine.prepare(opened.file, object : VoiceEngine.Listener {
                override fun onPrepared(durationMs: Int) {
                    if (mine != token) return
                    a.prepared = true
                    engine.start()
                    _state.update { it.copy(playing = true, durationMs = durationMs) }
                    startTicker(a)
                }

                override fun onCompleted() {
                    if (mine == token) stop()
                }

                override fun onError() {
                    if (mine == token) fail(messageId)
                }
            })
        }
    }

    private fun pause(a: Active) {
        a.engine.pause()
        ticker?.cancel()
        _state.update { it.copy(playing = false, positionMs = a.engine.positionMs) }
    }

    private fun resume(a: Active) {
        a.engine.start()
        _state.update { it.copy(playing = true) }
        startTicker(a)
    }

    private fun startTicker(a: Active) {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(tickMs)
                _state.update { it.copy(positionMs = a.engine.positionMs) }
            }
        }
    }

    private fun fail(messageId: String) {
        teardown()
        _state.value = VoiceState(failedId = messageId)
    }

    /** Cancels loading and ticking, frees the player, deletes the plaintext, and outdates everything still on its way. */
    private fun teardown() {
        token++
        loading?.cancel()
        loading = null
        ticker?.cancel()
        ticker = null
        active?.let {
            it.engine.release()
            it.opened.close()
        }
        active = null
    }
}

/** Elapsed or total time of a voice message: minutes and seconds, "0:07", "1:05". */
fun voiceClock(ms: Int): String {
    val seconds = maxOf(0, ms) / 1000
    val rest = seconds % 60
    return "${seconds / 60}:${if (rest < 10) "0" else ""}$rest"
}
