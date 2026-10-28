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
 * - on [ConnectionState.Revoked] (a 401 or close code 4003 on the authenticated socket, the only proof): [wipe],
 *   retried up to [WIPE_ATTEMPTS] times. After that nothing more runs, even if the wipe never succeeded.
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

    /** Collects [states] and [events] in the scope until it is cancelled or this device is revoked. */
    fun start(states: Flow<ConnectionState>, events: Flow<ServerEvent>) {
        jobs += scope.launch {
            states.distinctUntilChanged().collect { state ->
                pass?.cancel()
                pass = when (state) {
                    ConnectionState.Connected -> scope.launch { onConnected() }
                    // Not the pass: a later state change must not cancel the wipe's retries.
                    ConnectionState.Revoked -> { scope.launch { wipeThenStop() }; null }
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
            } catch (e: Exception) {
                log("$what failed: ${e.javaClass.simpleName}")
                false
            }
        }
        if (stopped) stop()
        return outcome
    }

    /**
     * Wipes, holding the lock across retries so nothing runs on half-wiped data: up to [WIPE_ATTEMPTS] tries with
     * backoff from [FIRST_WIPE_RETRY_MS]. All work stops afterwards, whether the wipe succeeded or not.
     */
    private suspend fun wipeThenStop() {
        work.withLock {
            if (stopped) return@withLock
            stopped = true
            var wait = FIRST_WIPE_RETRY_MS
            for (attempt in 1..WIPE_ATTEMPTS) {
                try {
                    wipe()
                    return@withLock
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("wipe failed: ${e.javaClass.simpleName}")
                }
                if (attempt < WIPE_ATTEMPTS) {
                    delay(wait)
                    wait *= 2
                }
            }
            log("wipe gave up")
        }
        stop()
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

        /** Tries at wiping before giving up; all work stops either way. */
        const val WIPE_ATTEMPTS = 5
        const val FIRST_WIPE_RETRY_MS = 1_000L
    }
}
