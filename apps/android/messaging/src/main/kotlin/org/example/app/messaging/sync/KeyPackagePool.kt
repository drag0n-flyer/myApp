package org.example.app.messaging.sync

import org.example.app.contracts.DeliveryApi
import org.example.app.contracts.MlsEngine
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.MlsLock
import org.example.app.messaging.ports.Repository

/**
 * Keeps this device's KeyPackages on the server: checks the server's count at most every 6 hours and uploads
 * [REFILL] when fewer than [LOW_WATER] are left. Generating them stores private keys in the MLS storage, so that
 * step runs under the [MlsLock]; the count and the upload are network calls and run outside it.
 */
class KeyPackagePool(
    private val mls: MlsEngine,
    private val server: DeliveryApi,
    private val repo: Repository,
    private val lock: MlsLock,
    private val clock: Clock,
) {
    suspend fun refillIfDue() {
        val now = clock.now()
        val last = repo.get(CHECKED_AT_KEY)?.decodeToString()?.toLongOrNull()
        if (last != null && now - last in 0 until CHECK_INTERVAL_MS) return
        if (server.keyPackageCount() < LOW_WATER) {
            val packages = lock.withLock { mls.makeKeyPackages(REFILL) }
            server.uploadKeyPackages(packages)
        }
        // Only a completed check counts; a failure above is retried on the next call.
        repo.set(CHECKED_AT_KEY, now.toString().encodeToByteArray())
    }

    companion object {
        const val CHECKED_AT_KEY = "kp.checkedAt"
        const val CHECK_INTERVAL_MS = 6L * 3600 * 1000
        const val LOW_WATER = 10
        const val REFILL = 50
    }
}
