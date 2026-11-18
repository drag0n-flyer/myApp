package org.example.app.ui.media

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.app.R
import org.example.app.media.StartResult
import org.example.app.media.StopResult
import org.example.app.media.VoiceFailure
import org.example.app.media.VoiceRecorder

/** The recorder as the screen uses it, so the rules above it can be tested without a microphone. */
interface RecorderPort {
    fun start(): StartResult
    fun stop(): StopResult
    fun cancel()
    val capturedSeconds: Double
}

class VoiceRecorderPort(private val recorder: VoiceRecorder, private val outputDir: File) : RecorderPort {
    override fun start(): StartResult {
        outputDir.mkdirs()
        return recorder.start()
    }

    override fun stop() = recorder.stop()
    override fun cancel() = recorder.cancel()
    override val capturedSeconds: Double get() = recorder.capturedSeconds
}

/** What the recording bar shows. */
sealed interface CaptureState {
    data object Idle : CaptureState
    /** [stopping]: Send was pressed and the note is being finished; the buttons wait. */
    data class Recording(val seconds: Double, val stopping: Boolean = false) : CaptureState
}

/**
 * Records one voice message at a time for a chat screen: [begin] starts (the screen has asked for the microphone
 * permission first; the recorder checks it again), [send] finishes the note and hands its file to [onNote], [cancel] throws
 * it away. Every outcome that is not a note is either nothing (a tap that was too short) or one line through [notify] in
 * plain words. Starting and finishing wait on the recording thread, so they run on [io], never on the main thread.
 * [release] when the screen leaves: a recording in progress is thrown away and nothing is started afterwards.
 */
class VoiceCapture(
    private val scope: CoroutineScope,
    private val recorder: RecorderPort,
    private val onNote: (File) -> Unit,
    private val notify: (Int) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val tickMs: Long = 200,
) {
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state

    private var ticker: Job? = null
    private var starting = false
    private var released = false

    fun begin() {
        if (released || starting || _state.value != CaptureState.Idle) return
        starting = true
        scope.launch {
            try {
                when (val result = withContext(io) { recorder.start() }) {
                    StartResult.Started -> if (released) {
                        cancelInBackground()
                    } else {
                        _state.value = CaptureState.Recording(0.0)
                        startTicker()
                    }
                    is StartResult.Failed -> notify(textFor(result.reason))
                }
            } catch (e: CancellationException) {
                // The screen left while the microphone was being opened: whatever it started is thrown away.
                cancelInBackground()
                throw e
            } finally {
                starting = false
            }
        }
    }

    fun send() {
        val current = _state.value as? CaptureState.Recording ?: return
        if (current.stopping) return
        _state.value = CaptureState.Recording(recorder.capturedSeconds, stopping = true)
        ticker?.cancel()
        scope.launch {
            // The note is kept here as well: if the screen leaves now, withContext drops it and it must not stay on disk.
            val held = AtomicReference<StopResult?>(null)
            val result = try {
                withContext(io + NonCancellable) { recorder.stop().also { held.set(it) } }
            } catch (e: CancellationException) {
                (held.get() as? StopResult.Success)?.recording?.file?.delete()
                throw e
            }
            _state.value = CaptureState.Idle
            when (result) {
                is StopResult.Success -> onNote(result.recording.file)
                // An accidental tap: nothing to send and nothing to say.
                StopResult.TooShort -> Unit
                is StopResult.Failed -> notify(textFor(result.reason))
            }
        }
    }

    fun cancel() {
        val current = _state.value as? CaptureState.Recording ?: return
        if (current.stopping) return
        ticker?.cancel()
        _state.value = CaptureState.Idle
        scope.launch { withContext(io + NonCancellable) { recorder.cancel() } }
    }

    /** The screen is leaving. Safe from the main thread: the recorder is cancelled on a thread of its own. */
    fun release() {
        released = true
        ticker?.cancel()
        if (_state.value is CaptureState.Recording) {
            _state.value = CaptureState.Idle
            cancelInBackground()
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(tickMs)
                _state.update { if (it is CaptureState.Recording && !it.stopping) CaptureState.Recording(recorder.capturedSeconds) else it }
            }
        }
    }

    // The recorder's cancel waits for the recording thread, so it never runs on the caller's thread.
    private fun cancelInBackground() {
        thread(name = "voice-cancel") { recorder.cancel() }
    }

    private fun textFor(reason: VoiceFailure): Int = when (reason) {
        VoiceFailure.Permission -> R.string.media_mic_off
        VoiceFailure.Microphone -> R.string.media_mic_unavailable
        VoiceFailure.Encoder, VoiceFailure.Invalid, VoiceFailure.Storage -> R.string.media_record_failed
    }
}
