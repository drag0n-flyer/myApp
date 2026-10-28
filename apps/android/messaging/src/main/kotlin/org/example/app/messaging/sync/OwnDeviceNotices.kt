package org.example.app.messaging.sync

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import org.example.app.contracts.DeliveryApi
import org.example.app.messaging.ports.Repository

/** Shows "New device added" (Design 19). The app posts a local notification. */
fun interface DeviceNotifier {
    suspend fun ownDeviceAdded(deviceId: String, name: String)
}

/**
 * One "New device added" notice per own device ID, ever. `IncomingHandler` wrote a pending marker per new own device
 * in the transaction that applied the commit, so every pass here posts all pending notices, also ones whose first
 * attempt was cut short. The name comes from `GET /v1/devices`; when that fails the marker stays and the next pass
 * tries again. A device the server no longer lists gets [FALLBACK_NAME]. The pending marker is cleared after the
 * notice and the `notified.device.<id>` marker, so a kill in between repeats the notice rather than losing it.
 * No lock is held: this is a network call.
 */
class OwnDeviceNotices(
    private val repo: Repository,
    private val server: DeliveryApi,
    private val notifier: DeviceNotifier,
) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    suspend fun post() {
        val pending = repo.entries(PENDING_PREFIX).filterValues { it.isNotEmpty() }.keys.map { it.removePrefix(PENDING_PREFIX) }.sorted()
        // Claimed before the first suspension point, so two passes cannot both post one ID.
        val todo = pending.filter { inFlight.add(it) }
        if (todo.isEmpty()) return
        try {
            val devices = try {
                server.devices()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
            for (id in todo) {
                if (repo.get(notifiedKey(id))?.isNotEmpty() == true) {
                    repo.set(pendingKey(id), ByteArray(0))
                    continue
                }
                val name = devices.firstOrNull { it.id == id }?.name.orEmpty()
                notifier.ownDeviceAdded(id, name.ifEmpty { FALLBACK_NAME })
                repo.set(notifiedKey(id), byteArrayOf(1))
                repo.set(pendingKey(id), ByteArray(0))
            }
        } finally {
            inFlight.removeAll(todo.toSet())
        }
    }

    companion object {
        const val PENDING_PREFIX = "notice.pending."
        const val FALLBACK_NAME = "A new device"
        fun pendingKey(deviceId: String) = PENDING_PREFIX + deviceId
        fun notifiedKey(deviceId: String) = "notified.device.$deviceId"
    }
}
