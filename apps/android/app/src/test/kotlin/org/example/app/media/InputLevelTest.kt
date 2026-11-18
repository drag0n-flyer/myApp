package org.example.app.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InputLevelTest {
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, v -> out[2 * i] = (v and 0xFF).toByte(); out[2 * i + 1] = (v shr 8).toByte() }
        return out
    }

    @Test fun silenceIsZero() = assertEquals(0f, InputLevel.of(pcm(0, 0, 0, 0), 8))

    @Test fun fullScaleIsOne() = assertEquals(1f, InputLevel.of(pcm(32767, -32768, 32767, -32768), 8), 0.001f)

    @Test fun quietSpeechStillMovesTheMeter() {
        val level = InputLevel.of(pcm(500, -500, 500, -500), 8)
        assertTrue(level in 0.1f..0.6f, "level=$level")
    }

    @Test fun onlyTheBytesReadCount() = assertEquals(0f, InputLevel.of(pcm(0, 0, 32767, 32767), 4))

    @Test fun nothingReadIsZero() = assertEquals(0f, InputLevel.of(ByteArray(8), 0))
}
