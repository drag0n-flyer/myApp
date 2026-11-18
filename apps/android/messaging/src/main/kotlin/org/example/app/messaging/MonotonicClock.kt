package org.example.app.messaging

import org.example.app.messaging.ports.Clock

/**
 * The store's clock: inside a burst (calls within a second of each other on a clock that has not moved) every time is one
 * later than the last, so messages made one after the other keep their order; otherwise it is the device clock itself, so a
 * clock that jumped and was corrected, or many calls from polling, never leave times in the future.
 */
internal class MonotonicClock(private val clock: Clock) : Clock {
    private var last = Long.MIN_VALUE

    override fun now(): Long = synchronized(this) {
        val c = clock.now()
        val t = if (last != Long.MIN_VALUE && last - c in 0..1000) last + 1 else c
        last = t
        t
    }
}
