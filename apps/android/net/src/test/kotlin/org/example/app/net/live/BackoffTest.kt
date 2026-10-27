package org.example.app.net.live

import kotlin.test.Test
import kotlin.test.assertEquals

class BackoffTest {
    @Test
    fun backoffGrowsAndCaps() {
        val bounds = mutableListOf<Double>()
        val b = Backoff { bound -> bounds += bound; bound }
        val waits = List(9) { b.next() }
        assertEquals(listOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 60.0, 60.0, 60.0), waits)
        assertEquals(waits, bounds)
    }

    @Test
    fun fullJitterUsesTheWholeRange() {
        val low = Backoff { 0.0 }
        assertEquals(0.0, low.next())
        val half = Backoff { it / 2 }
        assertEquals(0.5, half.next())
        assertEquals(1.0, half.next())
    }

    @Test
    fun resetStartsOver() {
        val b = Backoff { it }
        b.next(); b.next(); b.next()
        b.reset()
        assertEquals(1.0, b.next())
    }

    @Test
    fun hugeAttemptCountsDoNotOverflow() {
        val b = Backoff { it }
        repeat(500) { b.next() }
        assertEquals(60.0, b.next())
    }
}
