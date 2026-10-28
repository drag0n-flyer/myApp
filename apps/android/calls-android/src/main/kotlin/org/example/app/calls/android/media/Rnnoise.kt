package org.example.app.calls.android.media

import org.example.app.calls.android.BuildConfig

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

/** The native calls behind [Rnnoise]; a seam so tests can run without the native library. */
internal interface RnnoiseNative {
    /** A new denoiser state, or 0 on failure. */
    fun create(): Long

    /** Denoises [frame] in place and returns the voice probability. */
    fun process(handle: Long, frame: FloatArray): Float

    fun destroy(handle: Long)
}

/** The JNI functions in `librnnoise_jni.so` (src/main/cpp/rnnoise_jni.c). */
internal object RnnoiseJni : RnnoiseNative {
    init {
        System.loadLibrary("rnnoise_jni")
    }

    override fun create(): Long = nativeCreate()

    override fun process(handle: Long, frame: FloatArray): Float = nativeProcess(handle, frame)

    override fun destroy(handle: Long) = nativeDestroy(handle)

    @JvmStatic private external fun nativeCreate(): Long

    @JvmStatic private external fun nativeProcess(handle: Long, frame: FloatArray): Float

    @JvmStatic private external fun nativeDestroy(handle: Long)
}

/**
 * The vendored RNNoise (src/main/cpp/rnnoise, BSD-3-Clause) through JNI. Use one instance per
 * capture stream and [close] it when the call ends. [denoise] and [close] are synchronized, so
 * [close] never frees the state while a frame is being processed. An instance that is never
 * closed frees its state when it is finalized (java.lang.ref.Cleaner needs API 33; minSdk is 29).
 */
class Rnnoise internal constructor(private val native: RnnoiseNative) : FrameDenoiser, AutoCloseable {
    constructor() : this(RnnoiseJni)

    private var handle: Long = native.create().also { check(it != 0L) { "rnnoise_create failed" } }

    @Synchronized
    override fun denoise(frame: FloatArray) {
        require(frame.size == RNNOISE_FRAME_SAMPLES) { "RNNoise needs $RNNOISE_FRAME_SAMPLES-sample frames, got ${frame.size}" }
        check(handle != 0L) { "RNNoise is closed" }
        native.process(handle, frame)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            native.destroy(handle)
            handle = 0L
        }
    }

    @Suppress("ProtectedInFinal", "Unused")
    protected fun finalize() {
        close()
    }
}

/** The native WebRTC capture hook in `librnnoise_jni.so` (rnnoise_jni.c). */
internal object CaptureHookJni {
    init {
        System.loadLibrary("rnnoise_jni")
    }

    @JvmStatic external fun nativeProcessor(): Long

    @JvmStatic external fun nativeSetEnabled(on: Boolean)

    @JvmStatic external fun nativeDenoisedFrames(): Long

    @JvmStatic external fun nativeLastFrameSize(): Int

    @JvmStatic external fun nativeResetForCall()

    @JvmStatic external fun nativeSetTone(on: Boolean)

    @JvmStatic external fun nativeToneFrames(): Long
}

/**
 * RNNoise in WebRTC's capture path (patch 0007's `AudioCapturePostProcessing`): native code, so audio frames
 * never cross JNI. One hook per process, as the peer connection factory is one per process; [enabled] turns
 * RNNoise on for Enhanced. It denoises 480-sample frames (10 ms at 48 kHz) and passes other rates through.
 */
object CaptureHook {
    /** The address of the hook's `AppAudioCaptureProcessor`, for `AudioCapturePostProcessing`. */
    internal fun processor(): Long = CaptureHookJni.nativeProcessor()

    var enabled: Boolean = false
        set(value) {
            field = value
            CaptureHookJni.nativeSetEnabled(value)
        }

    /** Frames RNNoise has denoised since the process started. */
    val denoisedFrames: Long get() = CaptureHookJni.nativeDenoisedFrames()

    /** The size of the last frame the hook saw (480 at 48 kHz), or 0 before any. */
    val lastFrameSize: Int get() = CaptureHookJni.nativeLastFrameSize()

    /** Drops RNNoise's history before the next frame, so a call does not start from the last call's state. */
    fun resetForCall() = CaptureHookJni.nativeResetForCall()

    /**
     * Test stimulus: replaces the captured audio with a 440 Hz tone, so loopback tests send real audio. Debug
     * builds only: the native code is not in release builds, so the setter does nothing there.
     */
    internal var tone: Boolean = false
        set(value) {
            if (!BuildConfig.DEBUG) return
            field = value
            CaptureHookJni.nativeSetTone(value)
        }

    /** Frames the tone has been written into; 0 in release builds. */
    internal val toneFrames: Long get() = if (BuildConfig.DEBUG) CaptureHookJni.nativeToneFrames() else 0L
}
