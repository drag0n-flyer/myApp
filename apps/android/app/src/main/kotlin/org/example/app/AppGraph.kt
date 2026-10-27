package org.example.app

import android.content.Context
import org.example.app.core.account.AccountService
import org.example.app.core.account.SampleAccountService
import org.example.app.core.calls.CallEngine
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.calls.SimulatedCallEngine
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import org.example.app.core.settings.UserSettings

/**
 * Manual wiring of the services the screens use. Until the live services land (plan Task I1) both
 * values of [sampleData] select the sample services.
 */
class AppGraph(context: Context, val sampleData: Boolean) {
    private val appContext = context.applicationContext

    val accountService: AccountService by lazy { SampleAccountService() }
    val chatStore: ChatStore by lazy { SampleChatStore() }
    // Sample values until the live server, settings and network monitor exist.
    val serverPolicy = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    val userSettings = UserSettings()
    val network = NetworkType.Wifi
    val callEngine: CallEngine by lazy {
        SimulatedCallEngine(server = serverPolicy, settings = userSettings.call, network = network)
    }
}
