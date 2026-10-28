package org.example.app.calls.android.media

import org.example.app.core.settings.NoiseSuppression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioSetupTest {
    @Test
    fun fieldTrialEnablesOpusPlc() {
        assertEquals("WebRTC-Audio-OpusGeneratePlc/Enabled/", AudioSetup.FIELD_TRIALS)
    }

    @Test
    fun hardwareEchoCancellerAndNoiseSuppressorAreOff() {
        assertEquals(false, AudioSetup.USE_HARDWARE_ACOUSTIC_ECHO_CANCELER)
        assertEquals(false, AudioSetup.USE_HARDWARE_NOISE_SUPPRESSOR)
    }

    @Test
    fun noiseSettingMapsToProcessing() {
        assertEquals(
            AudioProcessing(echoCancellation = true, webRtcNoiseSuppression = false, rnnoise = false),
            AudioSetup.processing(NoiseSuppression.Off),
        )
        assertEquals(
            AudioProcessing(echoCancellation = true, webRtcNoiseSuppression = true, rnnoise = false),
            AudioSetup.processing(NoiseSuppression.Standard),
        )
        assertEquals(
            AudioProcessing(echoCancellation = true, webRtcNoiseSuppression = false, rnnoise = true),
            AudioSetup.processing(NoiseSuppression.Enhanced),
        )
    }

    @Test
    fun enhancedRunsRnnoise() {
        val frames = mutableListOf<Int>()
        val hook = AudioSetup.captureProcessor(NoiseSuppression.Enhanced) { FrameDenoiser { frames += it.size } }
        assertNotNull(hook)
        hook.process(ShortArray(2 * RNNOISE_FRAME_SAMPLES))
        assertEquals(listOf(480, 480), frames)
    }

    @Test
    fun standardAndOffDoNotRunRnnoise() {
        var made = false
        assertNull(AudioSetup.captureProcessor(NoiseSuppression.Standard) { made = true; FrameDenoiser {} })
        assertNull(AudioSetup.captureProcessor(NoiseSuppression.Off) { made = true; FrameDenoiser {} })
        assertTrue(!made)
    }

    @Test
    fun captureProcessorWritesDenoisedSamplesBackClamped() {
        val hook = assertNotNull(
            AudioSetup.captureProcessor(NoiseSuppression.Enhanced) {
                FrameDenoiser { frame -> for (i in frame.indices) frame[i] = frame[i] * 2f }
            },
        )
        val samples = ShortArray(RNNOISE_FRAME_SAMPLES) { 100 }.also { it[0] = 30_000; it[1] = -30_000 }
        hook.process(samples)
        assertEquals(Short.MAX_VALUE, samples[0])
        assertEquals(Short.MIN_VALUE, samples[1])
        assertEquals(200.toShort(), samples[2])
    }

    @Test
    fun captureProcessorRejectsPartialFrames() {
        val hook = assertNotNull(AudioSetup.captureProcessor(NoiseSuppression.Enhanced) { FrameDenoiser {} })
        assertFailsWith<IllegalArgumentException> { hook.process(ShortArray(100)) }
    }
}
