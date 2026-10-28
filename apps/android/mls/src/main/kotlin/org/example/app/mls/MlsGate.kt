package org.example.app.mls

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One MLS call at a time inside this process. The Rust engine re-reads its state from disk on
 * every call, so two overlapping calls on one storage directory could lose an update.
 * Not re-entrant: never call [withLock] from inside a block that holds it.
 */
object MlsGate {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
