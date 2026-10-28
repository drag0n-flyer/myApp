package org.example.app.messaging.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.ConnectionState
import org.example.app.contracts.ServerEvent

class OwnDeviceWatcherTest {
    private class Setup(scope: TestScope) {
        val log = ArrayList<String>()
        val states = MutableStateFlow(ConnectionState.Disconnected)
        val events = MutableSharedFlow<ServerEvent>()
        var failReconcile = false
        var failRevoked = false
        val watcher = OwnDeviceWatcher(
            scope = scope.backgroundScope,
            syncInbox = { log += "sync" },
            reconcile = { log += "reconcile"; if (failReconcile) error("down") },
            handleRevoked = { id -> log += "revoked:$id"; if (failRevoked) error("down") },
            refreshDevices = { log += "devices" },
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
    fun aFailedReconcileStillSchedulesTheFollowUp() = runTest {
        val s = Setup(this)
        s.failReconcile = true
        s.states.value = ConnectionState.Connected
        runCurrent()
        advanceTimeBy(eleven)
        runCurrent()
        assertEquals(listOf("sync", "reconcile", "reconcile"), s.log)
    }

    @Test
    fun deviceRevokedRunsTheRevokedHandlerThenRefreshesTheDeviceList() = runTest {
        val s = Setup(this)
        runCurrent()
        s.events.emit(ServerEvent.DeviceRevoked("d9"))
        runCurrent()
        assertEquals(listOf("revoked:d9", "devices"), s.log)
        // A failure in the handler still refreshes the list, and later events are still handled.
        s.failRevoked = true
        s.events.emit(ServerEvent.DeviceRevoked("d8"))
        runCurrent()
        s.events.emit(ServerEvent.DeviceRevoked("d7"))
        runCurrent()
        assertEquals(listOf("revoked:d9", "devices", "revoked:d8", "devices", "revoked:d7", "devices"), s.log)
    }
}
