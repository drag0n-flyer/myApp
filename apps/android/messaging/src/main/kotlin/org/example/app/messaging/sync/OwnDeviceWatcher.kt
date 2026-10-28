package org.example.app.messaging.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.example.app.contracts.ConnectionState
import org.example.app.contracts.ServerEvent

/**
 * Drives the own-device work from the live connection (mls-profile "Removing a device"):
 * - on every connect: the inbox sync, then [reconcile] (`DeviceSync.reconcileOwnDevices`). Removal needs two device
 *   lists at least ten minutes apart, so while the connection stays up, [reconcile] runs once more [RECHECK_MS] later.
 *   A disconnect cancels that follow-up; the next connect starts over.
 * - on `device.revoked`: [handleRevoked] (`DeviceSync.handleRevoked`, which wipes when it is this device), then
 *   [refreshDevices] for the device list.
 * Failures are swallowed: the next connect or event tries again. Time comes from the coroutine clock (`delay`).
 */
class OwnDeviceWatcher(
    private val scope: CoroutineScope,
    private val syncInbox: suspend () -> Unit,
    private val reconcile: suspend () -> Unit,
    private val handleRevoked: suspend (String) -> Unit,
    private val refreshDevices: suspend () -> Unit,
) {
    private var pass: Job? = null

    /** Collects [states] and [events] in the scope until it is cancelled. */
    fun start(states: Flow<ConnectionState>, events: Flow<ServerEvent>) {
        scope.launch {
            states.distinctUntilChanged().collect { state ->
                pass?.cancel()
                pass = if (state == ConnectionState.Connected) scope.launch { onConnected() } else null
            }
        }
        scope.launch {
            events.collect { event ->
                if (event is ServerEvent.DeviceRevoked) {
                    quietly { handleRevoked(event.deviceId) }
                    quietly { refreshDevices() }
                }
            }
        }
    }

    private suspend fun onConnected() {
        quietly { syncInbox() }
        quietly { reconcile() }
        delay(RECHECK_MS)
        quietly { reconcile() }
    }

    private suspend fun quietly(body: suspend () -> Unit) {
        try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    companion object {
        /** More than the ten minutes `DeviceSync` needs between two lists before it removes a device. */
        const val RECHECK_MS = 11 * 60_000L
    }
}
