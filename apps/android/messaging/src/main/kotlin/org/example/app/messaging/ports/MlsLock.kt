package org.example.app.messaging.ports

/**
 * The outer MLS lock: callers hold it around an engine call plus the database write that goes with it.
 * The app passes `:mls`'s `MlsGate` (`:messaging` is a JVM module and cannot depend on the Android `:mls`):
 *
 *     object : MlsLock { override suspend fun <T> withLock(block: suspend () -> T) = MlsGate.withLock(block) }
 *
 * `LiveMlsEngine` also has its own inner lock; this one makes "engine call + rows" one step.
 * Never held across a network call. Not re-entrant: never call [withLock] from inside a block that holds it.
 */
interface MlsLock {
    suspend fun <T> withLock(block: suspend () -> T): T
}
