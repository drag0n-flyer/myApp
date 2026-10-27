package org.example.app.messaging.ports

/** Wall-clock time in epoch milliseconds. Tests pass a fixed or stepping clock. */
fun interface Clock {
    fun now(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
