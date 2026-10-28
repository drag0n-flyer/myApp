package org.example.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.example.app.contracts.LiveSocket
import org.example.app.core.account.AccountService
import org.example.app.core.account.SampleAccountService
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.calls.SimulatedCallEngine
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import org.example.app.core.settings.UserSettings
import org.example.app.messaging.sync.DeviceSync
import org.example.app.messaging.sync.OwnDeviceWatcher
import org.example.app.ui.settings.SettingsStore

/**
 * Manual wiring of the services the screens use. Until the live services land (plan Task I1) both
 * values of [sampleData] select the sample services.
 */
class AppGraph(
    context: Context,
    val sampleData: Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val appContext = context.applicationContext

    val accountService: AccountService by lazy { SampleAccountService() }
    val chatStore: ChatStore by lazy { SampleChatStore() }
    // Sample values until the live server, settings and network monitor exist.
    val serverPolicy = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    /** The single source of truth for the user's settings; consumers read it as a flow. */
    val settingsStore = SettingsStore(UserSettings())
    val network = NetworkType.Wifi
    /** Signals that the device list may have changed (`device.revoked`); the linked devices screen reloads. */
    val deviceListChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Own-device work on the live connection (`OwnDeviceWatcher`): reconcile on every connect, `device.revoked`, and
     * the wipe when the socket says this device was revoked. For the live wiring (plan Task I1) to start once signed in.
     */
    fun watchOwnDevices(socket: LiveSocket, deviceSync: DeviceSync, syncInbox: suspend () -> Unit) =
        OwnDeviceWatcher(
            scope = scope,
            syncInbox = syncInbox,
            reconcile = deviceSync::reconcileOwnDevices,
            handleRevoked = deviceSync::handleRevoked,
            refreshDevices = { deviceListChanged.emit(Unit) },
            wipe = deviceSync::wipeLocalData,
            log = { Log.w("OwnDevices", it) },
        ).start(socket.states, socket.events)

    val callEngine: SimulatedCallEngine by lazy {
        SimulatedCallEngine(server = serverPolicy, settings = settingsStore.settings.value.call, network = network).also { engine ->
            settingsStore.settings.map { it.call }.distinctUntilChanged().onEach { engine.settings = it }.launchIn(scope)
        }
    }
}
