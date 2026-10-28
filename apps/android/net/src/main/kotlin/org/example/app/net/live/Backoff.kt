package org.example.app.net.live

import kotlin.math.min

/** Full-jitter exponential backoff in seconds: `random(0, min(cap, base * 2^attempt))`. [random] maps a bound to a draw in `0..bound`. */
class Backoff(
    private val base: Double = 1.0,
    private val cap: Double = 60.0,
    private val random: (Double) -> Double,
) {
    private var attempt = 0

    fun next(): Double {
        val bound = min(cap, base * Math.pow(2.0, min(attempt, 62).toDouble()))
        attempt += 1
        return random(bound)
    }

    fun reset() {
        attempt = 0
    }
}
