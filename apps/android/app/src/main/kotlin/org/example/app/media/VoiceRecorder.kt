package org.example.app.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.File
import org.example.app.messaging.media.MediaPlan

/**
 * Records a voice note as Ogg Opus (RFC 7845), 16 kHz mono, 16 kbps. Audio comes from [AudioRecord] and is encoded
 * with the platform's `audio/opus` MediaCodec when the phone has one, else with the libopus bundled in the project's
 * WebRTC build ([VoiceEncoder.open]); the packets are wrapped by [VoiceNoteFinisher] with the same Ogg writer the
 * iPhone uses (pre-skip 312 either way), so the file does not depend on the phone's encoder or `MediaRecorder` muxer.
 *
 * - [start] checks RECORD_AUDIO and returns [StartResult.Failed] instead of throwing.
 * - [stop] always says what happened: [StopResult.Success], [StopResult.TooShort] or [StopResult.Failed].
 * - At 30 minutes the recording stops by itself and keeps the note: [isRecording] turns false, [onLimitReached] is
 *   called (on the recording thread), and the next [stop] returns the finished note.
 */
class VoiceRecorder(
    private val context: Context,
    private val outputDir: File,
    private val onLimitReached: (() -> Unit)? = null,
) {
    private class Session(val record: AudioRecord, val encoder: VoiceEncoder) {
        @Volatile var stopRequested = false
        @Volatile var samples = 0L
        val slot = NoteSlot()
        lateinit var thread: Thread
        val packets = ArrayList<ByteArray>()
    }

    private var session: Session? = null

    val isRecording: Boolean get() = session?.let { it.slot.result == null } == true

    /** Seconds of audio captured so far (0 until the microphone delivers its first frame, which can be late). */
    val capturedSeconds: Double get() = (session?.samples ?: 0L).toDouble() / RATE

    /** The encoder of the last successful [start]. */
    @Volatile var lastEncoder: VoiceEncoderKind? = null
        private set

    /** The pre-skip written in the last finished note's Ogg header (48 kHz samples). */
    @Volatile var lastPreSkip: Int? = null
        private set

    internal var encoderChoice = VoiceEncoderChoice.PlatformFirst

    fun start(): StartResult {
        if (session != null) return StartResult.Failed(VoiceFailure.Microphone)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return StartResult.Failed(VoiceFailure.Permission)
        }
        var record: AudioRecord? = null
        var encoder: VoiceEncoder? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) return StartResult.Failed(VoiceFailure.Microphone)
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_BYTES * 8),
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return StartResult.Failed(VoiceFailure.Microphone) }
            encoder = VoiceEncoder.open(encoderChoice)
            if (encoder == null) {
                record.release()
                return StartResult.Failed(VoiceFailure.Encoder)
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("microphone busy")
        } catch (e: SecurityException) {
            runCatching { record?.release() }; runCatching { encoder?.release() }
            return StartResult.Failed(VoiceFailure.Permission)
        } catch (e: Exception) {
            runCatching { record?.release() }; runCatching { encoder?.release() }
            return StartResult.Failed(VoiceFailure.Microphone)
        }
        val s = Session(record, encoder)
        lastEncoder = encoder.kind
        s.thread = Thread({ run(s) }, "voice-recorder").also { it.start() }
        session = s
        return StartResult.Started
    }

    /** Ends the recording and returns the result; after [cancel] or without [start] it is `Failed`. */
    fun stop(): StopResult {
        val s = session ?: return StopResult.Failed(VoiceFailure.Microphone)
        session = null
        s.stopRequested = true
        s.thread.join(JOIN_MILLIS)
        if (s.thread.isAlive) {
            // Whatever the thread produces later is deleted, never left behind.
            s.slot.abandon(); s.thread.interrupt(); return StopResult.Failed(VoiceFailure.Encoder)
        }
        return s.slot.result ?: StopResult.Failed(VoiceFailure.Encoder)
    }

    /** Stops and throws the recording away. */
    fun cancel() {
        val s = session ?: return
        session = null
        s.stopRequested = true
        // Deletes a note the auto-stop already finished, and one that is still being written.
        s.slot.abandon()
        s.thread.join(JOIN_MILLIS)
    }

    private fun run(s: Session) {
        var failure: VoiceFailure? = null
        val buf = ByteArray(FRAME_BYTES)
        try {
            while (!s.stopRequested && !Thread.currentThread().isInterrupted) {
                if (s.samples >= VoiceNoteFinisher.MAX_CAPTURE_SAMPLES) { onLimitReached?.invoke(); break }
                val n = s.record.read(buf, 0, buf.size)
                if (n < 0) { failure = VoiceFailure.Microphone; break }
                if (n == 0) continue
                s.encoder.encode(buf, n, s.samples * 1_000_000L / RATE, s.packets)
                s.samples += n / 2
            }
            if (failure == null) s.encoder.finish(s.samples * 1_000_000L / RATE, s.packets)
        } catch (e: Exception) {
            failure = failure ?: VoiceFailure.Encoder
        } finally {
            runCatching { s.record.stop() }
            runCatching { s.record.release() }
            runCatching { s.encoder.release() }
        }
        s.slot.offer(
            when {
                failure != null -> StopResult.Failed(failure)
                // Stopped before the microphone delivered anything: too short, not an encoder failure (the bundled
                // encoder emits no packet for no audio).
                s.samples == 0L && s.packets.isEmpty() -> StopResult.TooShort
                else -> VoiceNoteFinisher.finish(s.packets, outputDir, s.encoder.preSkip.also { lastPreSkip = it })
            },
        )
    }

    private companion object {
        const val RATE = MediaPlan.Voice.SAMPLE_RATE
        /** 20 ms of 16-bit mono: one Opus frame. */
        const val FRAME_BYTES = RATE / 50 * 2
        const val JOIN_MILLIS = 5_000L
    }
}
