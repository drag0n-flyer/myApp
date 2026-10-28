package org.example.app.media

import java.io.File
import java.io.IOException
import org.example.app.messaging.media.MediaPlan
import org.example.app.messaging.media.ogg.OggOpusException
import org.example.app.messaging.media.ogg.OggOpusStream
import org.example.app.messaging.media.ogg.OggOpusWriter
import org.example.app.messaging.media.ogg.OpusPacket
import org.example.app.messaging.media.ogg.VoiceNoteException
import org.example.app.messaging.media.ogg.VoiceNoteValidator

/** A finished recording: [file] is Ogg Opus that passed [VoiceNoteValidator], [durationSeconds] its play time. */
class Recording(val file: File, val durationSeconds: Double)

/** Why a voice note could not be recorded. The UI maps each to plain words; none is silent. */
enum class VoiceFailure { Permission, Microphone, Encoder, Invalid, Storage }

sealed interface StartResult {
    object Started : StartResult
    class Failed(val reason: VoiceFailure) : StartResult
}

sealed interface StopResult {
    class Success(val recording: Recording) : StopResult
    /** Shorter than 0.5 s: an accidental tap. Nothing was kept. */
    object TooShort : StopResult
    class Failed(val reason: VoiceFailure) : StopResult
}

/**
 * Holds the finished note between the recording thread and the caller, so a note is either handed over or deleted:
 * [abandon] (from `cancel()`, or from a `stop()` that timed out) deletes a note that is ready and deletes one that
 * arrives later through [offer].
 */
class NoteSlot {
    private var value: StopResult? = null
    private var abandoned = false

    val result: StopResult? get() = synchronized(this) { value }

    fun offer(r: StopResult) = synchronized(this) {
        value = r
        if (abandoned) discard(r)
    }

    fun abandon() = synchronized(this) {
        abandoned = true
        value?.let { discard(it) }
    }

    private fun discard(r: StopResult) { if (r is StopResult.Success) r.recording.file.delete() }
}

/**
 * Turns the Opus packets from the encoder into the file that is sent: wraps them with the ported
 * [OggOpusWriter] (the same writer the iPhone uses, so the granules are computed, not guessed), checks the bytes
 * with [VoiceNoteValidator], and only then writes the file. Pure Kotlin, so it is tested with synthetic frames.
 */
object VoiceNoteFinisher {
    /**
     * The standard pre-skip, 312 samples in 48 kHz units (RFC 7845): the bundled encoder's, as on the iPhone. A phone
     * encoder that reports its own pre-skip gets that one instead ([VoiceEncoder.preSkip]).
     */
    const val DEFAULT_PRE_SKIP = 312

    /**
     * Capture stops this early before 30 minutes (16 kHz samples): two 20 ms frames of headroom, so a frame of
     * misalignment or a look-ahead frame at end of stream cannot push a full-length note over the validator's cap.
     */
    const val MAX_CAPTURE_SAMPLES: Long = (MediaPlan.Voice.MAX_DURATION * MediaPlan.Voice.SAMPLE_RATE).toLong() - 2 * 320

    fun finish(packets: List<ByteArray>, outputDir: File, preSkip: Int = DEFAULT_PRE_SKIP): StopResult {
        if (packets.isEmpty()) return StopResult.Failed(VoiceFailure.Encoder)
        val samples = packets.sumOf { (OpusPacket.samples48k(it) ?: return StopResult.Failed(VoiceFailure.Encoder)).toLong() }
        if (MediaPlan.Voice.tooShort(samples / 48_000.0)) return StopResult.TooShort
        val bytes: ByteArray
        val stream: OggOpusStream
        try {
            bytes = OggOpusWriter.write(packets, preSkip, MediaPlan.Voice.SAMPLE_RATE.toLong(), MediaPlan.Voice.CHANNELS)
            stream = VoiceNoteValidator.validate(bytes)
        } catch (e: VoiceNoteException) {
            return StopResult.Failed(VoiceFailure.Invalid)
        } catch (e: OggOpusException) {
            return StopResult.Failed(VoiceFailure.Invalid)
        }
        if (MediaPlan.Voice.tooShort(stream.durationSeconds)) return StopResult.TooShort
        var out: File? = null
        return try {
            outputDir.mkdirs()
            out = File.createTempFile("voice", ".${MediaPlan.Voice.FILE_EXTENSION}", outputDir).also { it.writeBytes(bytes) }
            StopResult.Success(Recording(out, MediaPlan.Voice.clamped(stream.durationSeconds)))
        } catch (e: IOException) {
            out?.delete()
            StopResult.Failed(VoiceFailure.Storage)
        }
    }
}
