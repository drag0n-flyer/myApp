package org.example.app.messaging.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.app.contracts.ConnectionState
import org.example.app.contracts.ServerEvent

/**
 * Drives the own-device work from the live connection (mls-profile "Removing a device"):
 * - on every connect: the inbox sync, then [reconcile] (`DeviceSync.reconcileOwnDevices`). Removal needs two device
 *   lists at least ten minutes apart, so once a reconcile succeeds, it runs once more [RECHECK_MS] later while the
 *   connection stays up. A failed reconcile is retried with backoff from [FIRST_RETRY_MS], doubling, capped at
 *   [RECHECK_MS]. A disconnect cancels what is pending; the next connect starts over.
 * - on `device.revoked`: [handleRevoked] (`DeviceSync.handleRevoked`: a hint that starts a reconcile; it never wipes
 *   or removes on its own), then [refreshDevices] for the device list.
 * - on [ConnectionState.Revoked] (a 401 or close code 4003 on the authenticated socket, the server's proof): [wipe].
 *   The same follows when a reconcile throws [DeviceSyncException.ThisDeviceRemoved] (it already wiped).
 *   After either, nothing more runs.
 *
 * All work runs one piece at a time under one lock, so a reconcile, a revoked event and a wipe never overlap.
 * Failures go to [log] as one line with IDs and the error's class only. Time comes from the coroutine clock.
 */
class OwnDeviceWatcher(
    private val scope: CoroutineScope,
    private val syncInbox: suspend () -> Unit,
    private val reconcile: suspend () -> Unit,
    private val handleRevoked: suspend (String) -> Unit,
    private val refreshDevices: suspend () -> Unit,
    private val wipe: suspend () -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val work = Mutex()
    private val jobs = ArrayList<Job>()
    @Volatile private var pass: Job? = null
    @Volatile private var stopped = false

    /** Signals [stop] from inside a piece of work. */
    private class Stop : Exception()

    /** Collects [states] and [events] in the scope until it is cancelled or this device is found removed. */
    fun start(states: Flow<ConnectionState>, events: Flow<ServerEvent>) {
        jobs += scope.launch {
            states.distinctUntilChanged().collect { state ->
                pass?.cancel()
                pass = when (state) {
                    ConnectionState.Connected -> scope.launch { onConnected() }
                    ConnectionState.Revoked -> scope.launch { serially("wipe") { wipe(); throw Stop() } }
                    else -> null
                }
            }
        }
        jobs += scope.launch {
            events.collect { event ->
                if (event is ServerEvent.DeviceRevoked) {
                    if (serially("device.revoked ${event.deviceId}") { handleRevoked(event.deviceId) } != null) {
                        try { refreshDevices() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    }
                }
            }
        }
    }

    private suspend fun onConnected() {
        serially("inbox sync") { syncInbox() }
        reconcileUntilDone()
        delay(RECHECK_MS)
        reconcileUntilDone()
    }

    private suspend fun reconcileUntilDone() {
        var wait = FIRST_RETRY_MS
        while (serially("own-device reconcile") { reconcile() } != true) {
            delay(wait)
            wait = minOf(wait * 2, RECHECK_MS)
        }
    }

    /**
     * Runs [body] under the lock unless stopped. True when it succeeded, false when it failed (logged), and null
     * when nothing ran because the watcher has stopped.
     */
    private suspend fun serially(what: String, body: suspend () -> Unit): Boolean? {
        val outcome = work.withLock {
            if (stopped) return@withLock null
            try {
                body()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Stop) {
                stopped = true
                null
            } catch (_: DeviceSyncException.ThisDeviceRemoved) {
                stopped = true
                null
            } catch (e: Exception) {
                log("$what failed: ${e.javaClass.simpleName}")
                false
            }
        }
        if (stopped) stop()
        return outcome
    }

    private fun stop() {
        pass?.cancel()
        jobs.forEach { it.cancel() }
        throw CancellationException("this device was removed")
    }

    companion object {
        /** More than the ten minutes `DeviceSync` needs between two lists before it removes a device. */
        const val RECHECK_MS = 11 * 60_000L
        const val FIRST_RETRY_MS = 30_000L
    }
}
