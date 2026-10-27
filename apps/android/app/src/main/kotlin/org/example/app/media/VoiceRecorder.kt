package org.example.app.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.File
import org.example.app.messaging.media.MediaPlan

/**
 * Records a voice note as Ogg Opus (RFC 7845), 16 kHz mono, 16 kbps. Audio comes from [AudioRecord] and is encoded
 * with the platform's `audio/opus` [MediaCodec] (API 29); the packets are wrapped by [VoiceNoteFinisher] with the
 * same Ogg writer the iPhone uses, so the file does not depend on a device's `MediaRecorder` muxer.
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
    private class Session(val record: AudioRecord, val codec: MediaCodec) {
        @Volatile var stopRequested = false
        @Volatile var cancelled = false
        @Volatile var result: StopResult? = null
        lateinit var thread: Thread
        val packets = ArrayList<ByteArray>()
        var preSkip = VoiceNoteFinisher.DEFAULT_PRE_SKIP
    }

    private var session: Session? = null

    val isRecording: Boolean get() = session?.let { it.result == null } == true

    fun start(): StartResult {
        if (session != null) return StartResult.Failed(VoiceFailure.Microphone)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return StartResult.Failed(VoiceFailure.Permission)
        }
        var record: AudioRecord? = null
        var codec: MediaCodec? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) return StartResult.Failed(VoiceFailure.Microphone)
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_BYTES * 8),
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return StartResult.Failed(VoiceFailure.Microphone) }
            val format = MediaFormat.createAudioFormat(MIME, RATE, MediaPlan.Voice.CHANNELS).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, MediaPlan.Voice.BIT_RATE)
            }
            codec = try {
                MediaCodec.createEncoderByType(MIME).also {
                    it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    it.start()
                }
            } catch (e: Exception) {
                record.release()
                return StartResult.Failed(VoiceFailure.Encoder)
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("microphone busy")
        } catch (e: SecurityException) {
            runCatching { record?.release() }; runCatching { codec?.release() }
            return StartResult.Failed(VoiceFailure.Permission)
        } catch (e: Exception) {
            runCatching { record?.release() }; runCatching { codec?.release() }
            return StartResult.Failed(VoiceFailure.Microphone)
        }
        val s = Session(record!!, codec!!)
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
        if (s.thread.isAlive) { s.cancelled = true; s.thread.interrupt(); return StopResult.Failed(VoiceFailure.Encoder) }
        return s.result ?: StopResult.Failed(VoiceFailure.Encoder)
    }

    /** Stops and throws the recording away. */
    fun cancel() {
        val s = session ?: return
        session = null
        s.cancelled = true
        s.stopRequested = true
        s.thread.join(JOIN_MILLIS)
    }

    private fun run(s: Session) {
        var failure: VoiceFailure? = null
        var samples = 0L
        val buf = ByteArray(FRAME_BYTES)
        val info = MediaCodec.BufferInfo()
        try {
            while (!s.stopRequested && !Thread.currentThread().isInterrupted) {
                if (samples >= MAX_SAMPLES) { onLimitReached?.invoke(); break }
                val n = s.record.read(buf, 0, buf.size)
                if (n < 0) { failure = VoiceFailure.Microphone; break }
                if (n == 0) continue
                feed(s, info, buf, n, samples * 1_000_000L / RATE, eos = false)
                samples += n / 2
            }
            if (failure == null) feed(s, info, ByteArray(0), 0, samples * 1_000_000L / RATE, eos = true)
        } catch (e: Exception) {
            failure = failure ?: VoiceFailure.Encoder
        } finally {
            runCatching { s.record.stop() }
            runCatching { s.record.release() }
            runCatching { s.codec.stop() }
            runCatching { s.codec.release() }
        }
        s.result = when {
            s.cancelled -> StopResult.Failed(VoiceFailure.Microphone)
            failure != null -> StopResult.Failed(failure)
            else -> VoiceNoteFinisher.finish(s.packets, s.preSkip, outputDir)
        }
    }

    /** Queues one input buffer (or end of stream), draining encoded packets so the encoder never stalls. */
    private fun feed(s: Session, info: MediaCodec.BufferInfo, data: ByteArray, size: Int, ptsUs: Long, eos: Boolean) {
        var queued = false
        var tries = 0
        while (!queued) {
            drain(s, info, endOfStream = false)
            val i = s.codec.dequeueInputBuffer(10_000)
            if (i >= 0) {
                s.codec.getInputBuffer(i)!!.apply { clear(); put(data, 0, size) }
                s.codec.queueInputBuffer(i, 0, size, ptsUs, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                queued = true
            } else if (++tries > 500) throw IllegalStateException("encoder stalled")
        }
        if (eos) drain(s, info, endOfStream = true)
    }

    private fun drain(s: Session, info: MediaCodec.BufferInfo, endOfStream: Boolean) {
        var idle = 0
        while (true) {
            val o = s.codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++idle > 500) throw IllegalStateException("encoder did not finish")
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readHead(s, s.codec.outputFormat.getByteBuffer("csd-0"))
                o >= 0 -> {
                    idle = 0
                    val out = s.codec.getOutputBuffer(o)!!
                    val bytes = ByteArray(info.size).also { out.position(info.offset); out.get(it) }
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (config) readHead(s, java.nio.ByteBuffer.wrap(bytes)) else if (bytes.isNotEmpty()) s.packets += bytes
                    s.codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun readHead(s: Session, csd: java.nio.ByteBuffer?) {
        val b = csd?.let { dup -> ByteArray(dup.remaining()).also { dup.duplicate().get(it) } }
        s.preSkip = VoiceNoteFinisher.preSkipFromOpusHead(b)
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS
        const val RATE = MediaPlan.Voice.SAMPLE_RATE
        /** 20 ms of 16-bit mono: one Opus frame. */
        const val FRAME_BYTES = RATE / 50 * 2
        const val MAX_SAMPLES = (MediaPlan.Voice.MAX_DURATION * RATE).toLong()
        const val JOIN_MILLIS = 5_000L
    }
}
