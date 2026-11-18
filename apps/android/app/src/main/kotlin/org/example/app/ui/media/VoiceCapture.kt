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
import org.example.app.core.chats.VoiceGesture
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
    /** The input level of the latest audio, 0 (silence) to 1 (loud); drives the level meter. */
    val level: Float get() = 0f
}

class VoiceRecorderPort(private val recorder: VoiceRecorder, private val outputDir: File) : RecorderPort {
    override fun start(): StartResult {
        outputDir.mkdirs()
        return recorder.start()
    }

    override fun stop() = recorder.stop()
    override fun cancel() = recorder.cancel()
    override val capturedSeconds: Double get() = recorder.capturedSeconds
    override val level: Float get() = recorder.level
}

/** What the composer shows in place of the message field. */
sealed interface CaptureState {
    data object Idle : CaptureState

    /**
     * The recording bar. [phase] is the gesture's state: recording (finger down), willCancel, willLock, or locked (finger
     * lifted). [levels] are the last [LEVEL_BARS] input levels, oldest first. [stopping]: Send or Stop was pressed and
     * the note is being finished; the buttons wait.
     */
    data class Recording(
        val seconds: Double,
        val stopping: Boolean = false,
        val levels: List<Float> = emptyList(),
        val phase: VoiceGesture.State = VoiceGesture.State.recording,
    ) : CaptureState

    /** A finished recording that has not been sent: play it, then Send or Delete. */
    data class Preview(val file: File, val seconds: Double, val sending: Boolean = false) : CaptureState
}

const val LEVEL_BARS = 30

/**
 * Records one voice message at a time for a chat screen. The touches arrive as [press], [drag], [lift], [stop], [delete],
 * [send], [limitReached], [interrupted] and [activate]; [VoiceGesture] decides what each one means. A recording is started
 * after the permission was asked for (the recorder checks it again), finished into a file that is handed to [onNote] or
 * kept as a [CaptureState.Preview], or thrown away: a note under a second, or one slid away from, is never sent and its
 * file is deleted by the recorder's cancel. Every outcome that is not a note is either nothing or one line through
 * [notify] in plain words. Starting and finishing wait on the recording thread, so they run on [io], never on the main
 * thread. [release] when the screen leaves: a recording in progress is thrown away and nothing is started afterwards.
 */
class VoiceCapture(
    private val scope: CoroutineScope,
    private val recorder: RecorderPort,
    private val onNote: (File) -> Unit,
    private val notify: (Int) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val tickMs: Long = 100,
    /** Run on [io] before a finished note is handed over; a note that fails it is deleted and "Couldn't record" is shown. */
    private val check: (File) -> Boolean = { true },
    /**
     * Where a recording goes when the screen leaves (or turns): stopped and kept as a preview for the next screen of the same
     * chat. Without it the recording is thrown away.
     */
    private val keep: ((CaptureState.Preview) -> Unit)? = null,
    /** A preview kept by the previous screen, shown again at once. */
    initial: CaptureState.Preview? = null,
) {
    private val _state = MutableStateFlow<CaptureState>(initial ?: CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state

    private val _hint = MutableStateFlow(false)

    /** True for two seconds after a tap or a too-short hold: "Hold to record, release to send". */
    val hint: StateFlow<Boolean> = _hint

    private var ticker: Job? = null
    private var hintJob: Job? = null
    private var holdJob: Job? = null
    private var gesture = if (initial != null) VoiceGesture.State.preview else VoiceGesture.State.idle
    private var rtl = false
    private var starting = false
    private var released = false

    private fun step(event: VoiceGesture.Event): VoiceGesture.State {
        val next = VoiceGesture.step(gesture, event, rtl)
        gesture = next
        return next
    }

    private fun elapsedMs() = (recorder.capturedSeconds * 1000).toLong()

    // ---- the finger ----

    /** The finger went down on the microphone (the permission is already granted). */
    fun press(rtl: Boolean) {
        if (released || starting || _state.value != CaptureState.Idle) return
        this.rtl = rtl
        if (step(VoiceGesture.Event.press) != VoiceGesture.State.pending) return
        holdJob?.cancel()
        holdJob = scope.launch {
            delay(VoiceGesture.startDelayMs)
            if (step(VoiceGesture.Event.heldFor(VoiceGesture.startDelayMs)) == VoiceGesture.State.recording) startRecorder()
        }
    }

    /** The finger moved to ([dx], [dy]) dp from where it went down. */
    fun drag(dx: Float, dy: Float) {
        if (!VoiceGesture.isRecording(gesture)) return
        val next = step(VoiceGesture.Event.drag(dx, dy))
        _state.update { if (it is CaptureState.Recording && !it.stopping) it.copy(phase = next) else it }
    }

    /** The finger lifted. */
    fun lift() {
        holdJob?.cancel()
        when (step(VoiceGesture.Event.release(elapsedMs()))) {
            VoiceGesture.State.tooShort -> { discard(); showHint() }
            VoiceGesture.State.cancelled -> discard()
            VoiceGesture.State.locked -> _state.update { if (it is CaptureState.Recording) it.copy(phase = VoiceGesture.State.locked) else it }
            VoiceGesture.State.send -> finish(thenSend = true)
            else -> Unit
        }
    }

    /** TalkBack, a keyboard or a pointer without drag: starts a locked recording at once. */
    fun activate() {
        if (released || starting || _state.value != CaptureState.Idle) return
        if (step(VoiceGesture.Event.activateAccessible) == VoiceGesture.State.locked) startRecorder()
    }

    /** Stop on the locked bar: ends the recording and shows the preview. */
    fun stop() {
        if ((_state.value as? CaptureState.Recording)?.stopping == true) return
        if (step(VoiceGesture.Event.stop) == VoiceGesture.State.preview) finish(thenSend = false)
    }

    /** Delete on the locked bar or the preview. */
    fun delete() {
        if (step(VoiceGesture.Event.delete) != VoiceGesture.State.cancelled) return
        when (val current = _state.value) {
            is CaptureState.Preview -> { _state.value = CaptureState.Idle; gesture = VoiceGesture.State.idle; deleteInBackground(current.file) }
            is CaptureState.Recording -> if (!current.stopping) discard()
            CaptureState.Idle -> Unit
        }
    }

    /** The 30-minute limit was reached (any thread): the note is kept as a preview and never sent by itself. */
    fun limitReached() {
        scope.launch { if (step(VoiceGesture.Event.limitReached) == VoiceGesture.State.preview) finish(thenSend = false) }
    }

    /** A call or the background took the screen: the recording is kept as a preview. */
    fun interrupted() {
        holdJob?.cancel()
        // Under a second is never worth keeping.
        if (VoiceGesture.isRecording(gesture) && _state.value is CaptureState.Recording && elapsedMs() < VoiceGesture.minimumMs) {
            discard()
            return
        }
        if (step(VoiceGesture.Event.interrupted) == VoiceGesture.State.preview) finish(thenSend = false)
    }

    /** A preview kept by an earlier screen arrives late (its recorder was still being stopped). */
    fun adopt(preview: CaptureState.Preview) {
        scope.launch {
            if (!released && _state.value == CaptureState.Idle && !starting) {
                gesture = VoiceGesture.State.preview
                _state.value = preview
            } else if (keep != null) keep.invoke(preview) else deleteInBackground(preview.file)
        }
    }

    private fun showHint() {
        hintJob?.cancel()
        _hint.value = true
        hintJob = scope.launch { delay(HINT_MS); _hint.value = false }
    }

    // ---- the recorder ----

    /** Starts a recording at once, as an unlocked hold would after the delay. */
    fun begin() {
        if (released || starting || _state.value != CaptureState.Idle) return
        gesture = VoiceGesture.State.recording
        startRecorder()
    }

    private fun startRecorder() {
        starting = true
        scope.launch {
            try {
                when (val result = withContext(io) { recorder.start() }) {
                    StartResult.Started -> if (released || !VoiceGesture.isRecording(gesture)) {
                        // The finger lifted (or the screen left) while the microphone was opening.
                        cancelInBackground()
                    } else {
                        _state.value = CaptureState.Recording(0.0, phase = gesture)
                        startTicker()
                    }
                    is StartResult.Failed -> { gesture = VoiceGesture.State.idle; notify(textFor(result.reason)) }
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

    /** Throws the recording away: bar closed at once, file deleted by the recorder's cancel. */
    private fun discard() {
        ticker?.cancel()
        val wasRecording = _state.value is CaptureState.Recording
        _state.value = CaptureState.Idle
        gesture = VoiceGesture.State.idle
        if (wasRecording) scope.launch { withContext(io + NonCancellable) { recorder.cancel() } }
    }

    /** Ends the recording: sends the note, or keeps it as a [CaptureState.Preview]. */
    private fun finish(thenSend: Boolean) {
        val current = _state.value as? CaptureState.Recording ?: return
        if (current.stopping) return
        _state.value = CaptureState.Recording(recorder.capturedSeconds, stopping = true, levels = current.levels, phase = gesture)
        ticker?.cancel()
        scope.launch {
            // The note is kept here as well: if the screen leaves now, withContext drops it and it must not stay on disk.
            val held = AtomicReference<StopResult?>(null)
            val result = try {
                withContext(io + NonCancellable) { recorder.stop().also { held.set(it) } }
            } catch (e: CancellationException) {
                val note = (held.get() as? StopResult.Success)?.recording
                if (note != null) {
                    if (!thenSend && keep != null) keep.invoke(CaptureState.Preview(note.file, note.durationSeconds)) else note.file.delete()
                }
                throw e
            }
            when (result) {
                is StopResult.Success -> {
                    val file = result.recording.file
                    if (!thenSend) {
                        gesture = VoiceGesture.State.preview
                        _state.value = CaptureState.Preview(file, result.recording.durationSeconds)
                    } else {
                        _state.value = CaptureState.Idle
                        gesture = VoiceGesture.State.idle
                        handOver(file)
                    }
                }
                // An accidental tap: nothing to send and nothing to say.
                StopResult.TooShort -> { _state.value = CaptureState.Idle; gesture = VoiceGesture.State.idle }
                is StopResult.Failed -> {
                    _state.value = CaptureState.Idle
                    gesture = VoiceGesture.State.idle
                    notify(textFor(result.reason))
                }
            }
        }
    }

    private suspend fun handOver(file: File) {
        if (withContext(io) { check(file) }) {
            onNote(file)
        } else {
            file.delete()
            notify(R.string.media_record_failed)
        }
    }

    /** Send: on the locked bar it finishes the note and sends it; on the preview it sends the kept file. */
    fun send() {
        when (val current = _state.value) {
            is CaptureState.Recording -> {
                if (current.stopping) return
                if (elapsedMs() < VoiceGesture.minimumMs) { discard(); showHint(); return }
                step(VoiceGesture.Event.sendTapped)
                finish(thenSend = true)
            }
            is CaptureState.Preview -> {
                if (current.sending) return
                if (current.seconds * 1000 < VoiceGesture.minimumMs) {
                    _state.value = CaptureState.Idle
                    gesture = VoiceGesture.State.idle
                    deleteInBackground(current.file)
                    showHint()
                    return
                }
                step(VoiceGesture.Event.sendTapped)
                _state.value = current.copy(sending = true)
                scope.launch {
                    _state.value = CaptureState.Idle
                    gesture = VoiceGesture.State.idle
                    handOver(current.file)
                }
            }
            CaptureState.Idle -> Unit
        }
    }

    /** Throws a recording or a preview away. */
    fun cancel() {
        when (val current = _state.value) {
            is CaptureState.Recording -> if (!current.stopping) discard()
            is CaptureState.Preview -> if (!current.sending) {
                _state.value = CaptureState.Idle
                gesture = VoiceGesture.State.idle
                deleteInBackground(current.file)
            }
            CaptureState.Idle -> Unit
        }
    }

    /** The screen is leaving. Safe from the main thread: the recorder is cancelled on a thread of its own. */
    fun release() {
        released = true
        ticker?.cancel()
        holdJob?.cancel()
        when (val current = _state.value) {
            is CaptureState.Recording -> {
                _state.value = CaptureState.Idle
                // A finish already under way keeps its own note (see finish); otherwise stop now and keep what was recorded.
                if (!current.stopping) if (keep != null) keepInBackground(keep) else cancelInBackground()
            }
            // If the app is killed, the sweep at the next start removes the file.
            is CaptureState.Preview -> {
                _state.value = CaptureState.Idle
                if (!current.sending) if (keep != null) keep.invoke(current) else deleteInBackground(current.file)
            }
            CaptureState.Idle -> Unit
        }
        gesture = VoiceGesture.State.idle
    }

    private fun deleteInBackground(file: File) {
        thread(name = "voice-delete") { file.delete() }
    }

    private fun keepInBackground(keep: (CaptureState.Preview) -> Unit) {
        thread(name = "voice-keep") {
            val result = recorder.stop()
            if (result is StopResult.Success) keep(CaptureState.Preview(result.recording.file, result.recording.durationSeconds))
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(tickMs)
                val level = recorder.level.coerceIn(0f, 1f)
                _state.update {
                    if (it is CaptureState.Recording && !it.stopping) {
                        it.copy(seconds = recorder.capturedSeconds, levels = (it.levels + level).takeLast(LEVEL_BARS))
                    } else it
                }
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

    private companion object {
        const val HINT_MS = 2_000L
    }
}
