package org.example.app.messaging.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.example.app.contracts.ConnectionState
import org.example.app.contracts.ServerEvent

class OwnDeviceWatcherTest {
    private class Setup(scope: TestScope) {
        val log = ArrayList<String>()
        val lines = ArrayList<String>()
        val states = MutableStateFlow(ConnectionState.Disconnected)
        val events = MutableSharedFlow<ServerEvent>()
        var reconcileFailures = 0
        var reconcileError: () -> Exception = { IllegalStateException("down") }
        var failRevoked = false
        /** When set, each reconcile waits for it without being cancellable, as a commit in flight does. */
        var gate: CompletableDeferred<Unit>? = null
        var active = 0
        var maxActive = 0
        private suspend fun work(name: String, body: suspend () -> Unit = {}) {
            active++; maxActive = maxOf(maxActive, active)
            try {
                log += name
                gate?.let { g -> withContext(NonCancellable) { g.await() } }
                body()
            } finally {
                active--
            }
        }
        val watcher = OwnDeviceWatcher(
            scope = scope.backgroundScope,
            syncInbox = { work("sync") },
            reconcile = { work("reconcile") { if (reconcileFailures > 0) { reconcileFailures--; throw reconcileError() } } },
            handleRevoked = { id -> work("revoked:$id") { if (failRevoked) error("down") } },
            refreshDevices = { log += "devices" },
            wipe = { work("wipe") },
            log = { lines += it },
        ).also { it.start(states, events) }
    }

    private val eleven = 11 * 60_000L

    @Test
    fun everyConnectSyncsTheInboxThenReconciles() = runTest {
        val s = Setup(this)
        runCurrent()
        assertEquals(emptyList(), s.log)
        s.states.value = ConnectionState.Connected
        runCurrent()
        assertEquals(listOf("sync", "reconcile"), s.log)
        s.states.value = ConnectionState.Disconnected
        runCurrent()
        s.states.value = ConnectionState.Connected
        runCurrent()
        assertEquals(listOf("sync", "reconcile", "sync", "reconcile"), s.log)
    }

    @Test
    fun whileStillConnectedItRunsOnceMoreElevenMinutesLater() = runTest {
        val s = Setup(this)
        s.states.value = ConnectionState.Connected
        runCurrent()
        advanceTimeBy(eleven - 1)
        runCurrent()
        assertEquals(listOf("sync", "reconcile"), s.log)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("sync", "reconcile", "reconcile"), s.log)
        // Only one follow-up per connect.
        advanceTimeBy(10 * eleven)
        runCurrent()
        assertEquals(3, s.log.size)
    }

    @Test
    fun aDisconnectCancelsTheFollowUp() = runTest {
        val s = Setup(this)
        s.states.value = ConnectionState.Connected
        runCurrent()
        s.states.value = ConnectionState.Disconnected
        runCurrent()
        advanceTimeBy(2 * eleven)
        runCurrent()
        assertEquals(listOf("sync", "reconcile"), s.log)
    }

    @Test
    fun aFailedReconcileIsLoggedAndRetriedWithBackoffCappedAtElevenMinutes() = runTest {
        val s = Setup(this)
        s.reconcileFailures = 20
        s.states.value = ConnectionState.Connected
        runCurrent()
        assertEquals(1, s.log.count { it == "reconcile" })
        assertEquals(listOf("own-device reconcile failed: IllegalStateException"), s.lines)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, s.log.count { it == "reconcile" }, "first retry after 30 s")
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, s.log.count { it == "reconcile" }, "then 60 s")
        // The wait doubles up to the 11 minutes and stays there.
        advanceTimeBy(120_000 + 240_000 + 480_000); runCurrent()
        assertEquals(6, s.log.count { it == "reconcile" })
        advanceTimeBy(eleven - 1); runCurrent()
        assertEquals(6, s.log.count { it == "reconcile" })
        advanceTimeBy(1); runCurrent()
        assertEquals(7, s.log.count { it == "reconcile" })
    }

    @Test
    fun aSuccessAfterRetriesStillGetsTheFollowUpElevenMinutesLater() = runTest {
        val s = Setup(this)
        s.reconcileFailures = 1
        s.states.value = ConnectionState.Connected
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, s.log.count { it == "reconcile" })
        advanceTimeBy(eleven); runCurrent()
        assertEquals(3, s.log.count { it == "reconcile" })
        advanceTimeBy(10 * eleven); runCurrent()
        assertEquals(3, s.log.count { it == "reconcile" })
    }

    @Test
    fun deviceRevokedRunsTheRevokedHandlerThenRefreshesTheDeviceList() = runTest {
        val s = Setup(this)
        runCurrent()
        s.events.emit(ServerEvent.DeviceRevoked("d9"))
        runCurrent()
        assertEquals(listOf("revoked:d9", "devices"), s.log)
        // A failure in the handler is logged with the ID, still refreshes the list, and later events still run.
        s.failRevoked = true
        s.events.emit(ServerEvent.DeviceRevoked("d8"))
        runCurrent()
        s.events.emit(ServerEvent.DeviceRevoked("d7"))
        runCurrent()
        assertEquals(listOf("revoked:d9", "devices", "revoked:d8", "devices", "revoked:d7", "devices"), s.log)
        assertEquals(
            listOf("device.revoked d8 failed: IllegalStateException", "device.revoked d7 failed: IllegalStateException"),
            s.lines,
        )
    }

    @Test
    fun flappingConnectsAndEventsNeverOverlap() = runTest {
        val s = Setup(this)
        val gate = CompletableDeferred<Unit>()
        s.gate = gate
        s.states.value = ConnectionState.Connected
        runCurrent()
        s.states.value = ConnectionState.Disconnected
        runCurrent()
        s.states.value = ConnectionState.Connected
        runCurrent()
        s.events.emit(ServerEvent.DeviceRevoked("d9"))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, s.maxActive)
        assertTrue(s.log.count { it == "reconcile" } >= 1)
        assertTrue("revoked:d9" in s.log)
    }

    @Test
    fun aRevokedConnectionWipesAndNothingRunsAfterwards() = runTest {
        val s = Setup(this)
        s.states.value = ConnectionState.Connected
        runCurrent()
        s.states.value = ConnectionState.Revoked
        runCurrent()
        assertEquals(listOf("sync", "reconcile", "wipe"), s.log)
        s.states.value = ConnectionState.Connected
        runCurrent()
        s.events.emit(ServerEvent.DeviceRevoked("d9"))
        advanceTimeBy(3 * eleven); runCurrent()
        assertEquals(listOf("sync", "reconcile", "wipe"), s.log)
    }

    @Test
    fun aReconcileThatFindsThisDeviceRemovedStopsEverything() = runTest {
        val s = Setup(this)
        s.reconcileFailures = 1
        s.reconcileError = { DeviceSyncException.ThisDeviceRemoved }
        s.states.value = ConnectionState.Connected
        runCurrent()
        advanceTimeBy(3 * eleven); runCurrent()
        s.events.emit(ServerEvent.DeviceRevoked("d9"))
        runCurrent()
        assertEquals(listOf("sync", "reconcile"), s.log)
    }
}
