package org.example.app.media

import kotlin.math.log10
import kotlin.math.sqrt

/** The loudness of a block of 16-bit little-endian mono PCM for the voice recorder's level meter. */
object InputLevel {
    /**
     * 0 for silence to 1 for full scale, on a decibel scale from -50 dBFS so quiet speech still moves the meter. Only the
     * first [bytes] bytes of [pcm] count.
     */
    fun of(pcm: ByteArray, bytes: Int): Float {
        val n = minOf(bytes, pcm.size) / 2
        if (n <= 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val v = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toInt()
            sum += v.toDouble() * v
        }
        val rms = sqrt(sum / n) / 32768.0
        if (rms <= 0.0) return 0f
        val db = 20 * log10(rms)
        return ((db + FLOOR_DB) / FLOOR_DB).toFloat().coerceIn(0f, 1f)
    }

    private const val FLOOR_DB = 50.0
}
