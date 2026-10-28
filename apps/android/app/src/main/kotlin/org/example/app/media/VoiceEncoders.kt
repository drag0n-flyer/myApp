package org.example.app.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import org.example.app.messaging.media.MediaPlan
import org.webrtc.OpusVoiceEncoder

/** Which Opus encoder made a voice note. Both feed the same [VoiceNoteFinisher], so the files are alike. */
enum class VoiceEncoderKind { Platform, Bundled }

/** Tests only: [BundledOnly] skips the platform encoder so the bundled one runs on every device. */
internal enum class VoiceEncoderChoice { PlatformFirst, BundledOnly }

/** 16 kHz mono 16-bit PCM in, Opus packets (20 ms each for the bundled encoder) out. Used by one thread. */
internal interface VoiceEncoder {
    val kind: VoiceEncoderKind

    /**
     * The pre-skip to write in the Ogg header, in 48 kHz samples: the phone encoder's own once it reported one (read
     * after [finish]), else [VoiceNoteFinisher.DEFAULT_PRE_SKIP]; always that 312 for the bundled encoder.
     */
    val preSkip: Int

    /** Encodes [size] bytes of PCM; [ptsUs] is the first sample's time. Appends finished packets to [out]. */
    fun encode(pcm: ByteArray, size: Int, ptsUs: Long, out: MutableList<ByteArray>)

    /** Ends the stream and appends the remaining packets. */
    fun finish(ptsUs: Long, out: MutableList<ByteArray>)

    fun release()

    companion object {
        /** The platform's `audio/opus` MediaCodec first, then the bundled libopus; null if neither starts. */
        fun open(choice: VoiceEncoderChoice): VoiceEncoder? {
            if (choice == VoiceEncoderChoice.PlatformFirst) PlatformVoiceEncoder.open()?.let { return it }
            return BundledVoiceEncoder.open()
        }
    }
}

/** Cuts PCM reads of any size into whole frames of [frameBytes]; [flush] pads the last partial frame with silence. */
internal class PcmFramer(private val frameBytes: Int) {
    private val frame = ByteArray(frameBytes)
    private var filled = 0

    fun push(data: ByteArray, size: Int, emit: (ByteArray) -> Unit) {
        var at = 0
        while (at < size) {
            val n = minOf(frameBytes - filled, size - at)
            System.arraycopy(data, at, frame, filled, n)
            filled += n
            at += n
            if (filled == frameBytes) { emit(frame.copyOf()); filled = 0 }
        }
    }

    fun flush(emit: (ByteArray) -> Unit) {
        if (filled == 0) return
        frame.fill(0, filled, frameBytes)
        emit(frame.copyOf())
        filled = 0
    }
}

/** The platform's `audio/opus` [MediaCodec] encoder (none on the API 29 AOSP image). */
internal class PlatformVoiceEncoder private constructor(private val codec: MediaCodec) : VoiceEncoder {
    override val kind = VoiceEncoderKind.Platform
    private val info = MediaCodec.BufferInfo()
    @Volatile private var reportedPreSkip: Int? = null
    override val preSkip: Int get() = reportedPreSkip ?: VoiceNoteFinisher.DEFAULT_PRE_SKIP

    override fun encode(pcm: ByteArray, size: Int, ptsUs: Long, out: MutableList<ByteArray>) =
        feed(pcm, size, ptsUs, eos = false, out)

    override fun finish(ptsUs: Long, out: MutableList<ByteArray>) = feed(ByteArray(0), 0, ptsUs, eos = true, out)

    override fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    /** Queues one input buffer (or end of stream), draining encoded packets so the encoder never stalls. */
    private fun feed(data: ByteArray, size: Int, ptsUs: Long, eos: Boolean, out: MutableList<ByteArray>) {
        var queued = false
        var tries = 0
        while (!queued) {
            drain(out, endOfStream = false)
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) {
                codec.getInputBuffer(i)!!.apply { clear(); put(data, 0, size) }
                codec.queueInputBuffer(i, 0, size, ptsUs, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                queued = true
            } else if (++tries > 500) throw IllegalStateException("encoder stalled")
        }
        if (eos) drain(out, endOfStream = true)
    }

    private fun drain(out: MutableList<ByteArray>, endOfStream: Boolean) {
        var idle = 0
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val head = runCatching { codec.outputFormat.getByteBuffer("csd-0") }.getOrNull()
                    head?.let { b -> OpusHeadPreSkip.parse(ByteArray(b.remaining()).also { b.duplicate().get(it) }) }
                        ?.let { reportedPreSkip = it }
                }
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++idle > 500) throw IllegalStateException("encoder did not finish")
                }
                o >= 0 -> {
                    idle = 0
                    val buffer = codec.getOutputBuffer(o)!!
                    val bytes = ByteArray(info.size).also { buffer.position(info.offset); buffer.get(it) }
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (config) OpusHeadPreSkip.parse(bytes)?.let { reportedPreSkip = it }
                    else if (bytes.isNotEmpty()) out += bytes
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        private const val TAG = "VoiceEncoder"

        fun open(): PlatformVoiceEncoder? {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS, MediaPlan.Voice.SAMPLE_RATE, MediaPlan.Voice.CHANNELS,
            ).apply { setInteger(MediaFormat.KEY_BIT_RATE, MediaPlan.Voice.BIT_RATE) }
            // No Opus encoder on this phone (the API 29 AOSP image): the bundled one is the normal path, nothing to log.
            val codec = try {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            } catch (e: Exception) {
                return null
            }
            return start(codec) {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
        }

        /**
         * Runs [begin] (configure and start) on a phone encoder that exists. If it fails, [log] gets the codec's name
         * and the error (never any recorded audio), the codec is released, and null makes the caller use the bundled one.
         */
        internal fun start(
            codec: MediaCodec,
            log: (String) -> Unit = { Log.w(TAG, it) },
            begin: (MediaCodec) -> Unit,
        ): PlatformVoiceEncoder? = try {
            begin(codec)
            PlatformVoiceEncoder(codec)
        } catch (e: Exception) {
            val name = runCatching { codec.name }.getOrNull() ?: MediaFormat.MIMETYPE_AUDIO_OPUS
            log("phone Opus encoder $name did not start (${e.javaClass.simpleName}: ${e.message}); using the bundled one")
            runCatching { codec.release() }
            null
        }
    }
}

/**
 * The libopus inside the project's WebRTC build ([OpusVoiceEncoder], tools/webrtc patch 0008): 16 kHz mono 16 kbps,
 * 20 ms packets. Its pre-skip must be the 312 that [VoiceNoteFinisher] writes, or it is not used.
 */
internal class BundledVoiceEncoder private constructor(private val encoder: OpusVoiceEncoder) : VoiceEncoder {
    override val kind = VoiceEncoderKind.Bundled
    override val preSkip = VoiceNoteFinisher.DEFAULT_PRE_SKIP
    private val framer = PcmFramer(OpusVoiceEncoder.FRAME_BYTES)

    override fun encode(pcm: ByteArray, size: Int, ptsUs: Long, out: MutableList<ByteArray>) =
        framer.push(pcm, size) { out += encoder.encode(it, 0) }

    override fun finish(ptsUs: Long, out: MutableList<ByteArray>) = framer.flush { out += encoder.encode(it, 0) }

    override fun release() = encoder.close()

    companion object {
        fun open(): BundledVoiceEncoder? {
            if (OpusVoiceEncoder.SAMPLE_RATE != MediaPlan.Voice.SAMPLE_RATE ||
                OpusVoiceEncoder.CHANNELS != MediaPlan.Voice.CHANNELS ||
                OpusVoiceEncoder.BIT_RATE != MediaPlan.Voice.BIT_RATE
            ) {
                return null
            }
            val encoder = try {
                OpusVoiceEncoder()
            } catch (e: Exception) {
                return null
            } catch (e: UnsatisfiedLinkError) {
                return null
            }
            if (encoder.preSkip() != VoiceNoteFinisher.DEFAULT_PRE_SKIP) { encoder.close(); return null }
            return BundledVoiceEncoder(encoder)
        }
    }
}

/**
 * Reads the pre-skip from an encoder's header: a plain RFC 7845 `OpusHead`, or Android's codec config, which wraps it
 * as `AOPUSHDR` + length + `OpusHead` (then `AOPUSDLY` and `AOPUSPRL`). Null when there is no whole `OpusHead`.
 */
internal object OpusHeadPreSkip {
    private val MAGIC = "OpusHead".toByteArray()
    private const val HEAD_SIZE = 19

    fun parse(bytes: ByteArray): Int? {
        for (at in 0..bytes.size - HEAD_SIZE) {
            if ((MAGIC.indices).all { bytes[at + it] == MAGIC[it] }) {
                return (bytes[at + 10].toInt() and 0xFF) or ((bytes[at + 11].toInt() and 0xFF) shl 8)
            }
        }
        return null
    }
}
