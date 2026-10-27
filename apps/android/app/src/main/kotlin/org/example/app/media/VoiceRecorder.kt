package org.example.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import org.example.app.messaging.media.MediaPlan
import org.example.app.messaging.media.ogg.VoiceNoteException
import org.example.app.messaging.media.ogg.VoiceNoteValidator

/** A finished recording: [file] is Ogg Opus that passed [VoiceNoteValidator], [durationSeconds] its play time. */
class Recording(val file: File, val durationSeconds: Double)

/**
 * Records a voice note as Ogg Opus (RFC 7845), 16 kHz mono, 16 kbps, straight from `MediaRecorder`
 * (`OutputFormat.OGG` + `AudioEncoder.OPUS`, API 29). The file is checked with the same rules the iPhone applies
 * to a received note; a recording that fails them, or is shorter than 0.5 s, is deleted and never offered.
 * The caller needs the RECORD_AUDIO permission before [start].
 */
class VoiceRecorder(private val context: Context, private val outputDir: File) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    @Suppress("DEPRECATION")
    fun start() {
        check(recorder == null) { "already recording" }
        outputDir.mkdirs()
        val out = File.createTempFile("voice", ".${MediaPlan.Voice.FILE_EXTENSION}", outputDir)
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioChannels(MediaPlan.Voice.CHANNELS)
            r.setAudioSamplingRate(MediaPlan.Voice.SAMPLE_RATE)
            r.setAudioEncodingBitRate(MediaPlan.Voice.BIT_RATE)
            r.setMaxDuration((MediaPlan.Voice.MAX_DURATION * 1000).toInt())
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
        } catch (t: Throwable) {
            runCatching { r.release() }
            out.delete()
            throw t
        }
        recorder = r
        file = out
        startedAt = System.nanoTime()
    }

    /**
     * Stops and returns the recording, or null when it was too short or the file is not valid Ogg Opus (it is
     * deleted in both cases).
     */
    fun stop(): Recording? {
        val r = recorder ?: return null
        val out = file!!
        recorder = null; file = null
        val stopped = runCatching { r.stop() }.isSuccess   // throws when nothing was recorded yet
        runCatching { r.release() }
        val measured = (System.nanoTime() - startedAt) / 1e9
        if (!stopped || MediaPlan.Voice.tooShort(measured)) { out.delete(); return null }
        return try {
            VoiceNoteValidator.checkFileSize(out.length())
            val stream = VoiceNoteValidator.validate(out.readBytes())
            if (MediaPlan.Voice.tooShort(stream.durationSeconds)) { out.delete(); null }
            else Recording(out, MediaPlan.Voice.clamped(stream.durationSeconds))
        } catch (e: VoiceNoteException) {
            out.delete(); null
        } catch (e: org.example.app.messaging.media.ogg.OggOpusException) {
            out.delete(); null
        }
    }

    /** Stops and throws the recording away. */
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
        file?.delete()
        file = null
    }
}
