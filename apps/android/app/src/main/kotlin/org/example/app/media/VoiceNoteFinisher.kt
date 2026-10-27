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
    /** 312 samples at 48 kHz: libopus's look-ahead, used when the encoder gives no readable OpusHead. */
    const val DEFAULT_PRE_SKIP = 312

    /**
     * Capture stops this early before 30 minutes (16 kHz samples): two 20 ms frames of headroom, so a frame of
     * misalignment or a look-ahead frame at end of stream cannot push a full-length note over the validator's cap.
     */
    const val MAX_CAPTURE_SAMPLES: Long = (MediaPlan.Voice.MAX_DURATION * MediaPlan.Voice.SAMPLE_RATE).toLong() - 2 * 320

    fun finish(packets: List<ByteArray>, preSkip: Int, outputDir: File): StopResult {
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

    /**
     * Pre-skip (little-endian u16 at offset 10 of an `OpusHead`) from the encoder's codec config, or the default.
     * Android's `audio/opus` encoder (C2SoftOpusEnc) sends `csd-0` as `"AOPUSHDR"`, a u64 little-endian length, then
     * the `OpusHead` (pre-skip is in 48 kHz samples, as RFC 7845 says); a bare `OpusHead` is accepted too.
     */
    fun preSkipFromOpusHead(csd: ByteArray?): Int {
        var head = csd ?: return DEFAULT_PRE_SKIP
        if (head.size >= 16 && String(head, 0, 8, Charsets.ISO_8859_1) == "AOPUSHDR") {
            var len = 0L
            for (i in 7 downTo 0) len = (len shl 8) or (head[8 + i].toLong() and 0xFF)
            if (len < 0 || len > head.size - 16) return DEFAULT_PRE_SKIP
            head = head.copyOfRange(16, 16 + len.toInt())
        }
        if (head.size < 12 || String(head, 0, 8, Charsets.ISO_8859_1) != "OpusHead") return DEFAULT_PRE_SKIP
        return (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
    }
}
