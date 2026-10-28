package org.example.app.messaging.testing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.app.messaging.ports.MlsLock

/** An [MlsLock] like `MlsGate` (one holder, not re-entrant) that tells whether it is held. */
class TestMlsLock : MlsLock {
    private val mutex = Mutex()
    val isHeld: Boolean get() = mutex.isLocked

    override suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
