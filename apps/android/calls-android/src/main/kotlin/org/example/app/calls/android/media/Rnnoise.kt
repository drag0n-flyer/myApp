package org.example.app.calls.android.media

/** RNNoise works on 10 ms mono frames at 48 kHz. */
const val RNNOISE_FRAME_SAMPLES = 480

/** Denoises one [RNNOISE_FRAME_SAMPLES]-sample frame in place, samples in 16-bit scale. */
fun interface FrameDenoiser {
    fun denoise(frame: FloatArray)
}

/**
 * Runs 16-bit 48 kHz mono capture audio through a [FrameDenoiser] in 480-sample frames.
 * The buffer length must be a whole number of frames (WebRTC captures 10 ms buffers).
 */
class CaptureProcessor(private val denoiser: FrameDenoiser) {
    private val frame = FloatArray(RNNOISE_FRAME_SAMPLES)

    fun process(samples: ShortArray) {
        require(samples.size % RNNOISE_FRAME_SAMPLES == 0) {
            "capture buffer of ${samples.size} samples is not a whole number of $RNNOISE_FRAME_SAMPLES-sample frames"
        }
        var start = 0
        while (start < samples.size) {
            for (i in 0 until RNNOISE_FRAME_SAMPLES) frame[i] = samples[start + i].toFloat()
            denoiser.denoise(frame)
            for (i in 0 until RNNOISE_FRAME_SAMPLES) {
                samples[start + i] = frame[i].coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
            }
            start += RNNOISE_FRAME_SAMPLES
        }
    }
}

/**
 * The vendored RNNoise (src/main/cpp/rnnoise, BSD-3-Clause) through JNI. Not thread-safe:
 * use one instance per capture stream, and [close] it when the call ends.
 */
class Rnnoise : FrameDenoiser, AutoCloseable {
    private var handle: Long = nativeCreate().also { check(it != 0L) { "rnnoise_create failed" } }

    override fun denoise(frame: FloatArray) {
        require(frame.size == RNNOISE_FRAME_SAMPLES) { "RNNoise needs $RNNOISE_FRAME_SAMPLES-sample frames, got ${frame.size}" }
        check(handle != 0L) { "RNNoise is closed" }
        nativeProcess(handle, frame)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private companion object {
        init {
            System.loadLibrary("rnnoise_jni")
        }

        @JvmStatic external fun nativeCreate(): Long

        @JvmStatic external fun nativeProcess(handle: Long, frame: FloatArray): Float

        @JvmStatic external fun nativeDestroy(handle: Long)
    }
}
