package org.example.app.calls.android.media

import org.example.app.core.settings.NoiseSuppression

/** Which audio processing a call runs (design §8.2). */
data class AudioProcessing(
    /** WebRTC's AEC3. */
    val echoCancellation: Boolean,
    /** WebRTC's own noise suppression. */
    val webRtcNoiseSuppression: Boolean,
    /** RNNoise in the capture hook, 48 kHz, 480-sample frames. */
    val rnnoise: Boolean,
)

/**
 * Audio settings shared by the media session. [WebRtcFactory] passes [FIELD_TRIALS] to
 * `PeerConnectionFactory.InitializationOptions` and builds the `JavaAudioDeviceModule` with the hardware flags
 * below; [WebRtcMediaSession] maps the noise setting with [processing]. In a call RNNoise runs natively in
 * WebRTC's capture hook ([CaptureHook]); [captureProcessor] is the same framing on 16-bit samples in Kotlin.
 */
object AudioSetup {
    const val FIELD_TRIALS = "WebRTC-Audio-OpusGeneratePlc/Enabled/"

    /** Hardware AEC and NS differ from maker to maker, so WebRTC's own run instead. */
    const val USE_HARDWARE_ACOUSTIC_ECHO_CANCELER = false
    const val USE_HARDWARE_NOISE_SUPPRESSOR = false

    fun processing(setting: NoiseSuppression): AudioProcessing = when (setting) {
        NoiseSuppression.Off -> AudioProcessing(echoCancellation = true, webRtcNoiseSuppression = false, rnnoise = false)
        NoiseSuppression.Standard -> AudioProcessing(echoCancellation = true, webRtcNoiseSuppression = true, rnnoise = false)
        NoiseSuppression.Enhanced -> AudioProcessing(echoCancellation = true, webRtcNoiseSuppression = false, rnnoise = true)
    }

    /**
     * The capture hook for [setting], or null when it runs no RNNoise. [makeDenoiser] is called
     * once, only for Enhanced; the default is the native [Rnnoise].
     */
    fun captureProcessor(
        setting: NoiseSuppression,
        makeDenoiser: () -> FrameDenoiser = { Rnnoise() },
    ): CaptureProcessor? = if (processing(setting).rnnoise) CaptureProcessor(makeDenoiser()) else null
}
