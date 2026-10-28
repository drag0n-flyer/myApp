package org.example.app

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.example.app.core.account.AccountState
import org.example.app.core.account.SampleAccountService
import org.example.app.ui.contacts.AddContactScreen
import org.example.app.ui.contacts.ContactsScreen
import org.example.app.ui.contacts.MessageRequestsScreen
import org.example.app.ui.settings.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.foundation.layout.Box
import org.example.app.core.calls.CallPhase
import org.example.app.core.calls.SimulatedCallEngine
import org.example.app.ui.call.CallScreen
import org.example.app.ui.call.CallViewModel
import org.example.app.ui.call.IncomingCallScreen
import org.example.app.ui.calls.CallHistoryScreen
import org.example.app.ui.calls.CallHistoryViewModel
import org.example.app.ui.chat.ChatScreen
import org.example.app.ui.chat.ChatViewModel
import org.example.app.ui.chatinfo.ChatInfoScreen
import org.example.app.ui.chatlist.ChatListScreen
import org.example.app.ui.chatlist.ChatListViewModel
import org.example.app.ui.nav.MainTab
import org.example.app.ui.nav.MainTabs
import org.example.app.ui.nav.TabPlaceholder
import org.example.app.ui.onboarding.LinkApprovalFlow
import org.example.app.ui.onboarding.LinkApprovalViewModel
import org.example.app.ui.onboarding.OnboardingHost
import org.example.app.ui.onboarding.OnboardingViewModel
import org.example.app.ui.theme.AppTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

private const val MAIN = "main"
private const val CHAT = "chat/{id}"
private const val INFO = "chatinfo/{id}"
private const val ONBOARDING = "onboarding"
/** The approving phone scans a new device's code (opened from Linked devices). */
const val LINK_SCAN = "linkscan"
private const val REQUESTS = "requests"
private const val ADD_CONTACT = "addcontact"

private fun shareInvite(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.invite_message, context.getString(R.string.app_name), url))
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Always true before Android 14, where the permission is granted at install. */
private fun canUseFullScreenIntent(context: Context): Boolean =
    Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

private fun openFullScreenSettings(context: Context) {
    if (Build.VERSION.SDK_INT < 34) return
    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** A factory for a view model built from the graph; the key keeps one instance per chat. */
private inline fun <reified T : ViewModel> factory(crossinline make: () -> T) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
}

@Composable
fun AppRoot(
    graph: AppGraph,
    // Sample mode goes straight to the chats; the live build starts with onboarding until signed in.
    startWithOnboarding: Boolean? = null,
) {
    AppTheme {
        // An account that cannot be read right now is not a signed-out one: ask again, never onboard over it.
        var accountState by remember { mutableStateOf(if (graph.sampleData) AccountState.SignedIn else graph.accountService.accountState) }
        if (startWithOnboarding == null && accountState == AccountState.Unavailable) {
            AccountUnavailableScreen(onTryAgain = { accountState = graph.accountService.accountState })
            return@AppTheme
        }
        val onboarding = startWithOnboarding ?: (accountState == AccountState.SignedOut)
        val nav = rememberNavController()
        val idArg = listOf(navArgument("id") { type = NavType.StringType })
        val context = LocalContext.current
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val callVm = viewModel<CallViewModel>(
            factory = factory {
                CallViewModel(
                    graph.callEngine, graph.chatStore, graph.serverPolicy, { graph.settingsStore.settings.value.call },
                    simulator = graph.callEngine as? SimulatedCallEngine, showSimulator = debuggable,
                )
            },
        )
        val call by callVm.call.collectAsStateWithLifecycle()
        Box {
        NavHost(nav, startDestination = if (onboarding) ONBOARDING else MAIN) {
            composable(ONBOARDING) {
                val vm = viewModel<OnboardingViewModel>(
                    factory = viewModelFactory { initializer { OnboardingViewModel(graph.accountService, saved = createSavedStateHandle()) } },
                )
                OnboardingHost(vm, onFinished = { nav.navigate(MAIN) { popUpTo(ONBOARDING) { inclusive = true } } })
            }
            composable(LINK_SCAN) {
                val vm = viewModel<LinkApprovalViewModel>(factory = factory { LinkApprovalViewModel(graph.accountService) })
                LinkApprovalFlow(vm, onDone = { nav.popBackStack() })
            }
            composable(MAIN) {
                MainTabs { tab ->
                    if (tab == MainTab.Chats) {
                        val vm = viewModel<ChatListViewModel>(
                            factory = factory { ChatListViewModel(graph.chatStore, graph.serverPolicy, graph.settingsStore.settings, graph.network) },
                        )
                        ChatListScreen(vm, onOpenChat = { nav.navigate("chat/$it") }, onOpenRequests = { nav.navigate(REQUESTS) }, onNewChat = {})
                    } else if (tab == MainTab.Calls) {
                        val vm = viewModel<CallHistoryViewModel>(factory = factory { CallHistoryViewModel(graph.chatStore) })
                        CallHistoryScreen(vm, onCall = callVm::start)
                    } else if (tab == MainTab.Contacts) {
                        val context = LocalContext.current
                        val invite = "https://${graph.serverPolicy.host}/invite/${SampleAccountService.SAMPLE_INVITE_CODE}"
                        ContactsScreen(
                            graph.chatStore, invite,
                            onOpenChat = { nav.navigate("chat/$it") },
                            onAddContact = { nav.navigate(ADD_CONTACT) },
                            onInvite = { shareInvite(context, it) },
                        )
                    } else if (tab == MainTab.Settings) {
                        var devices by remember { mutableIntStateOf(0) }
                        LaunchedEffect(Unit) { devices = runCatching { graph.accountService.devices().size }.getOrDefault(0) }
                        SettingsScreen(graph.chatStore.me, graph.serverPolicy.host, devices, onOpen = { nav.navigate(it.route) })
                    } else {
                        TabPlaceholder(tab)
                    }
                }
            }
            composable(REQUESTS) {
                MessageRequestsScreen(graph.chatStore, onOpenChat = { nav.navigate("chat/$it") }, onBack = { nav.popBackStack() })
            }
            composable(ADD_CONTACT) {
                AddContactScreen(graph.chatStore, onBack = { nav.popBackStack() }, onAdded = { nav.popBackStack() })
            }
            composable(SettingsPage.Server.route) { ServerScreen(graph.serverPolicy.host, onBack = { nav.popBackStack() }) }
            composable(SettingsPage.LinkedDevices.route) { LinkedDevicesScreen(graph.accountService, onBack = { nav.popBackStack() }, onLinkDevice = { nav.navigate(LINK_SCAN) }) }
            composable(SettingsPage.Privacy.route) { PrivacyScreen(graph.settingsStore, graph.chatStore, onBack = { nav.popBackStack() }) }
            composable(SettingsPage.DataStorage.route) { DataStorageScreen(graph.settingsStore, graph.serverPolicy, onBack = { nav.popBackStack() }) }
            composable(SettingsPage.ChatFolders.route) { ChatFoldersScreen(graph.chatStore, onBack = { nav.popBackStack() }) }
            composable(SettingsPage.CallsAudio.route) { CallsAudioScreen(graph.settingsStore, onBack = { nav.popBackStack() }) }
            composable(SettingsPage.Licenses.route) { LicensesScreen(onBack = { nav.popBackStack() }) }
            composable(SettingsPage.Notifications.route) {
                val context = LocalContext.current
                val delivery by graph.settingsStore.delivery.collectAsStateWithLifecycle()
                val fullScreenAllowed by rememberRefreshedOnResume { canUseFullScreenIntent(context) }
                NotificationsScreen(
                    delivery, graph.settingsStore::setDelivery,
                    canUseFullScreenIntent = fullScreenAllowed,
                    onOpenFullScreenSettings = { openFullScreenSettings(context) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(CHAT, idArg) { entry ->
                val id = entry.arguments!!.getString("id")!!
                val vm = viewModel<ChatViewModel>(
                    key = "chat-$id",
                    factory = factory { ChatViewModel(graph.chatStore, id, graph.network) { graph.settingsStore.settings.value.autoDownload } },
                )
                ChatScreen(
                    vm, myId = graph.chatStore.me.id,
                    onBack = { nav.popBackStack() },
                    onOpenInfo = { nav.navigate("chatinfo/$id") },
                    onCall = { video -> callVm.start(id, video) },
                )
            }
            composable(INFO, idArg) { entry ->
                val id = entry.arguments!!.getString("id")!!
                ChatInfoScreen(
                    graph.chatStore, id,
                    onBack = { nav.popBackStack() },
                    onCall = { video -> callVm.start(id, video) },
                    onSearch = {},
                    onBlocked = { nav.popBackStack(MAIN, inclusive = false) },
                )
            }
        }
        when {
            call == null -> {}
            call?.phase == CallPhase.Incoming -> IncomingCallScreen(callVm)
            else -> CallScreen(callVm)
        }
        }
    }
}
