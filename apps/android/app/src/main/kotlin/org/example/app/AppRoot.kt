package org.example.app

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.app.core.layout.PaneLayout
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.example.app.core.account.AccountState
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
import org.example.app.ui.chatlist.ArchiveScreen
import org.example.app.ui.chatlist.ChatListScreen
import org.example.app.ui.chatlist.ChatListViewModel
import org.example.app.ui.chatlist.NewChatScreen
import org.example.app.ui.search.SearchScreen
import org.example.app.ui.search.SearchViewModel
import org.example.app.push.PushRuntime
import org.example.app.push.PushTransport
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.app.ui.nav.MainTab
import org.example.app.ui.nav.MainTabs
import org.example.app.ui.nav.TabPlaceholder
import org.example.app.ui.onboarding.LinkApprovalFlow
import org.example.app.ui.onboarding.LinkApprovalViewModel
import org.example.app.ui.onboarding.OnboardingHost
import org.example.app.ui.onboarding.OnboardingViewModel
import org.example.app.ui.lock.AuthResult
import org.example.app.ui.lock.DeviceAuth
import org.example.app.ui.lock.LockCover
import org.example.app.ui.lock.LocalExpectReturn
import androidx.compose.runtime.CompositionLocalProvider
import org.example.app.ui.lock.PlatformDeviceAuth
import org.example.app.ui.lock.SampleDeviceAuth
import org.example.app.ui.theme.AppTheme
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

private const val MAIN = "main"
/** `highlight` is a message ID to scroll to and flash (a search hit); the chat screen reads it as `highlightMessageId`. */
private const val CHAT = "chat/{id}?highlight={highlight}"
private const val INFO = "chatinfo/{id}"
/** The approving phone scans a new device's code (opened from Linked devices). */
const val LINK_SCAN = "linkscan"
private const val REQUESTS = "requests"
private const val ARCHIVE = "archive"
private const val ADD_CONTACT = "addcontact"
private const val NEW_CHAT = "newchat"
private const val SEARCH = "search"
private const val PROFILE = "settings/profile"

private fun shareInvite(context: Context, url: String, expectReturn: () -> Unit) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.invite_message, context.getString(R.string.app_name), url))
    expectReturn()
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Always true before Android 14, where the permission is granted at install. */
private fun canUseFullScreenIntent(context: Context): Boolean =
    Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

private fun openFullScreenSettings(context: Context, expectReturn: () -> Unit) {
    if (Build.VERSION.SDK_INT < 34) return
    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    expectReturn()
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
    deviceAuth: DeviceAuth = defaultDeviceAuth(graph),
) {
    AppTheme {
        // An account that cannot be read right now is not a signed-out one: ask again, never onboard over it.
        var accountState by remember { mutableStateOf(graph.accountState) }
        if (startWithOnboarding == null && accountState == AccountState.Unavailable) {
            AccountUnavailableScreen(onTryAgain = { accountState = graph.accountState })
            return@AppTheme
        }
        // Onboarding is shown on its own, before anything reads the chat store (the live one needs a signed-in account).
        var onboarding by remember { mutableStateOf(startWithOnboarding ?: (accountState == AccountState.SignedOut)) }
        val sessionEnds by graph.sessionEnds.collectAsStateWithLifecycle()
        var seenEnds by remember { mutableIntStateOf(sessionEnds) }
        if (sessionEnds != seenEnds) {
            // Signed out by the server or wiped after a revocation: back to onboarding.
            seenEnds = sessionEnds
            onboarding = true
        }
        if (onboarding) {
            // A notification tapped while signed out opens nothing later.
            LaunchedEffect(graph) { graph.requestedChat.collect { if (it != null) graph.requestedChat.value = null } }
            val vm = viewModel<OnboardingViewModel>(
                key = "onboarding-$sessionEnds",
                factory = viewModelFactory { initializer { OnboardingViewModel(graph.accountService, saved = createSavedStateHandle()) } },
            )
            val opening = rememberCoroutineScope()
            // The signed-in services open off the main thread before the chats show.
            OnboardingHost(vm, onFinished = { opening.launch { graph.openSession(); onboarding = false } })
            return@AppTheme
        }
        var opened by remember(sessionEnds) { mutableStateOf(graph.sampleData || graph.liveSession != null) }
        if (!opened) {
            // Signed in at launch: open the session off the main thread; nothing reads the chats until then.
            LaunchedEffect(sessionEnds) { if (graph.openSession() == null) onboarding = true else opened = true }
            Box(Modifier.fillMaxSize())
            return@AppTheme
        }
        val nav = rememberNavController()
        // From 840 dp the list sits beside the open chat. The open chat, its info page and the message to flash are
        // kept across rotation; below 840 dp nothing of this shows and a chat is a screen of its own.
        val wide = PaneLayout.forWidth(LocalConfiguration.current.screenWidthDp) == PaneLayout.TwoPane
        var paneChat by rememberSaveable { mutableStateOf<String?>(null) }
        var paneInfo by rememberSaveable { mutableStateOf(false) }
        var paneHighlight by rememberSaveable { mutableStateOf<String?>(null) }
        val openChat = rememberUpdatedState<(String, String?) -> Unit> { id, highlight ->
            if (wide) {
                paneChat = id; paneInfo = false; paneHighlight = highlight
                // Any full-screen page over the main screen (new chat, search, ...) closes: the chat opens beside the list.
                if (nav.currentDestination?.route != MAIN) nav.popBackStack(MAIN, inclusive = false)
            } else {
                nav.navigate("chat/${Uri.encode(id)}" + if (highlight != null) "?highlight=${Uri.encode(highlight)}" else "")
            }
        }
        // A rotation or resize across the threshold carries the open chat (and its info page) into the other layout.
        LaunchedEffect(wide) {
            val route = nav.currentBackStackEntry?.destination?.route
            if (wide) {
                if (route == CHAT || route == INFO) {
                    paneChat = nav.currentBackStackEntry?.arguments?.getString("id")
                    paneInfo = route == INFO
                    paneHighlight = null
                    nav.popBackStack(MAIN, inclusive = false)
                }
            } else {
                paneChat?.let { id ->
                    paneChat = null
                    nav.navigate("chat/${Uri.encode(id)}")
                    if (paneInfo) nav.navigate("chatinfo/${Uri.encode(id)}")
                }
                paneInfo = false
            }
        }
        val idArg = listOf(navArgument("id") { type = NavType.StringType })
        val chatArgs = idArg + navArgument("highlight") { type = NavType.StringType; nullable = true; defaultValue = null }
        // While the app lock is on and locked, only the cover (and a ringing or active call) is composed.
        val lockSetting by graph.settingsStore.settings.collectAsState()
        val lockState by graph.appLock.locked.collectAsState()
        val locked = lockSetting.appLock && lockState
        // A message notification was tapped (also while the app was running): open that chat. Locked, it is held
        // until the unlock, then followed once (the key changes, and the request is cleared after it is followed).
        val requestedChat by graph.requestedChat.collectAsStateWithLifecycle()
        LaunchedEffect(requestedChat, locked) {
            if (locked) return@LaunchedEffect
            requestedChat?.let { id ->
                // Only a chat this account has: another app could send any ID.
                graph.openableChat(id)?.let { openChat.value(it, null) }
                graph.requestedChat.value = null
            }
        }
        val context = LocalContext.current
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // One per session: a call view model never outlives the account it was made for.
        val callVm = viewModel<CallViewModel>(
            key = "call-$sessionEnds",
            factory = factory {
                CallViewModel(
                    graph.callEngine, graph.chatStore, graph.serverPolicy, { graph.settingsStore.settings.value.call },
                    simulator = graph.callEngine as? SimulatedCallEngine, showSimulator = debuggable && graph.callsAvailable,
                )
            },
        )
        val call by callVm.call.collectAsStateWithLifecycle()
        if (locked) {
            Box {
                LockCover(graph.appLock, deviceAuth)
                CallOverlay(call?.phase, callVm)
            }
            return@AppTheme
        }
        CompositionLocalProvider(LocalExpectReturn provides graph.appLock::expectReturn) {
        Box {
        NavHost(nav, startDestination = MAIN) {
            composable(LINK_SCAN) {
                val vm = viewModel<LinkApprovalViewModel>(factory = factory { LinkApprovalViewModel(graph.accountService) })
                LinkApprovalFlow(vm, onDone = { nav.popBackStack() })
            }
            composable(MAIN) {
                val panes: @Composable (Modifier) -> Unit = { paneModifier ->
                MainTabs(modifier = paneModifier, tabs = if (graph.callsAvailable) MainTab.entries else MainTab.entries - MainTab.Calls) { tab ->
                    if (tab == MainTab.Chats) {
                        val vm = viewModel<ChatListViewModel>(
                            factory = factory { ChatListViewModel(graph.chatStore, graph.serverPolicy, graph.settingsStore.settings, graph.network, graph.connectionStatus) },
                        )
                        ChatListScreen(
                            vm, onOpenChat = { openChat.value(it, null) }, onOpenRequests = { nav.navigate(REQUESTS) },
                            onNewChat = { nav.navigate(NEW_CHAT) }, onSearch = { nav.navigate(SEARCH) },
                            onOpenArchive = { nav.navigate(ARCHIVE) },
                        )
                    } else if (tab == MainTab.Calls) {
                        val vm = viewModel<CallHistoryViewModel>(factory = factory { CallHistoryViewModel(graph.chatStore) })
                        CallHistoryScreen(vm, onCall = callVm::start)
                    } else if (tab == MainTab.Contacts) {
                        val context = LocalContext.current
                        ContactsScreen(
                            graph.chatStore, graph.inviteUrl,
                            onOpenChat = { openChat.value(it, null) },
                            onAddContact = { nav.navigate(ADD_CONTACT) },
                            onInvite = { shareInvite(context, it, graph.appLock::expectReturn) },
                        )
                    } else if (tab == MainTab.Settings) {
                        var devices by remember { mutableIntStateOf(0) }
                        LaunchedEffect(Unit) { devices = runCatching { graph.accountService.devices().size }.getOrDefault(0) }
                        SettingsScreen(graph.chatStore.meState.collectAsStateWithLifecycle().value, graph.serverPolicy.host, devices, onOpen = { nav.navigate(it.route) }, onOpenProfile = { nav.navigate(PROFILE) })
                    } else {
                        TabPlaceholder(tab)
                    }
                }
                }
                if (!wide) panes(Modifier) else {
                    // Back closes the info page, then the chat, before it leaves the app.
                    BackHandler(enabled = paneChat != null) { if (paneInfo) paneInfo = false else paneChat = null }
                    Row(Modifier.fillMaxSize()) {
                        panes(Modifier.width(PaneLayout.LIST_PANE_DP.dp))
                        VerticalDivider()
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            val id = paneChat
                            if (id == null) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.select_a_chat)) }
                            } else key(id) {
                                if (paneInfo) {
                                    ChatInfoScreen(
                                        graph.chatStore, id,
                                        onBack = { paneInfo = false },
                                        onCall = if (graph.callsAvailable) { video -> callVm.start(id, video) } else null,
                                        onSearch = {},
                                        onBlocked = { paneInfo = false; paneChat = null },
                                    )
                                } else {
                                    val vm = viewModel<ChatViewModel>(
                                        key = "chat-$id",
                                        factory = factory { ChatViewModel(graph.chatStore, id, graph.network) { graph.settingsStore.settings.value.autoDownload } },
                                    )
                                    ChatScreen(
                                        vm, myId = graph.chatStore.me.id,
                                        onBack = { paneChat = null },
                                        onOpenInfo = { paneInfo = true },
                                        onCall = if (graph.callsAvailable) { video -> callVm.start(id, video) } else null,
                                        onAccept = { graph.chatStore.accept(id) },
                                        onBlock = { contactId -> graph.chatStore.block(contactId); paneChat = null },
                                        onReport = { graph.chatStore.report(id) },
                                        onForward = { target -> if (target != id) openChat.value(target, null) },
                                        highlightMessageId = paneHighlight?.takeIf { it.isNotBlank() },
                                        opener = graph.attachmentOpener,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            composable(ARCHIVE) {
                val vm = viewModel<ChatListViewModel>(
                    factory = factory { ChatListViewModel(graph.chatStore, graph.serverPolicy, graph.settingsStore.settings, graph.network, graph.connectionStatus) },
                )
                ArchiveScreen(vm, onOpenChat = { openChat.value(it, null) }, onBack = { nav.popBackStack() })
            }
            composable(REQUESTS) {
                MessageRequestsScreen(graph.chatStore, onOpenChat = { openChat.value(it, null) }, onBack = { nav.popBackStack() })
            }
            composable(ADD_CONTACT) {
                AddContactScreen(graph.chatStore, onBack = { nav.popBackStack() }, onAdded = { nav.popBackStack() })
            }
            composable(NEW_CHAT) {
                NewChatScreen(
                    graph.chatStore,
                    onChoose = { contactId ->
                        val chatId = graph.chatStore.ensureChat(contactId)
                        if (wide) openChat.value(chatId, null)
                        else nav.navigate("chat/${Uri.encode(chatId)}") { popUpTo(NEW_CHAT) { inclusive = true } }
                    },
                    onAddContact = { nav.navigate(ADD_CONTACT) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(SEARCH) {
                val vm = viewModel<SearchViewModel>(factory = factory { SearchViewModel(graph.chatStore) })
                SearchScreen(
                    vm,
                    onOpenContact = { contactId -> openChat.value(graph.chatStore.ensureChat(contactId), null) },
                    onOpenMessage = { chatId, messageId -> openChat.value(chatId, messageId) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(PROFILE) {
                val vm = viewModel<ProfileViewModel>(factory = factory { ProfileViewModel(graph.accountActions) })
                val me = graph.chatStore.meState.collectAsStateWithLifecycle().value
                ProfileScreen(me, vm, onBack = { nav.popBackStack() }, storedName = graph.chatStore.myStoredName)
            }
            composable(SettingsPage.Server.route) {
                val status by graph.connectionStatus.collectAsStateWithLifecycle()
                ServerScreen(graph.serverPolicy.host, onBack = { nav.popBackStack() }, status = status)
            }
            composable(SettingsPage.LinkedDevices.route) { LinkedDevicesScreen(graph.accountService, onBack = { nav.popBackStack() }, onLinkDevice = { nav.navigate(LINK_SCAN) }, refreshes = graph.deviceListChanged) }
            composable(SettingsPage.Privacy.route) { PrivacyScreen(graph.settingsStore, graph.chatStore, graph.appLock, deviceAuth, saveFindByEmail = graph::saveFindByEmail, loadFindByEmail = graph::loadFindByEmail, onBack = { nav.popBackStack() }, onLockSwitchedOn = { PushRuntime.hideShownContent(context) }) }
            composable(SettingsPage.DataStorage.route) { DataStorageScreen(graph.settingsStore, graph.serverPolicy, onBack = { nav.popBackStack() }) }
            composable(SettingsPage.ChatFolders.route) { ChatFoldersScreen(graph.chatStore, onBack = { nav.popBackStack() }) }
            composable(SettingsPage.CallsAudio.route) { CallsAudioScreen(graph.settingsStore, onBack = { nav.popBackStack() }) }
            composable(SettingsPage.Licenses.route) { LicensesScreen(onBack = { nav.popBackStack() }) }
            composable(SettingsPage.Notifications.route) {
                val context = LocalContext.current
                val transport by produceState(PushTransport.None) { value = withContext(Dispatchers.IO) { graph.pushTransport() } }
                val fullScreenAllowed by rememberRefreshedOnResume { canUseFullScreenIntent(context) }
                NotificationsScreen(
                    transport,
                    canUseFullScreenIntent = fullScreenAllowed,
                    onOpenFullScreenSettings = { openFullScreenSettings(context, graph.appLock::expectReturn) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(CHAT, chatArgs) { entry ->
                val id = entry.arguments!!.getString("id")!!
                val vm = viewModel<ChatViewModel>(
                    key = "chat-$id",
                    factory = factory { ChatViewModel(graph.chatStore, id, graph.network) { graph.settingsStore.settings.value.autoDownload } },
                )
                ChatScreen(
                    vm, myId = graph.chatStore.me.id,
                    onBack = { nav.popBackStack() },
                    onOpenInfo = { nav.navigate("chatinfo/$id") },
                    onCall = if (graph.callsAvailable) { video -> callVm.start(id, video) } else null,
                    onAccept = { graph.chatStore.accept(id) },
                    onBlock = { contactId ->
                        graph.chatStore.block(contactId)
                        nav.popBackStack(MAIN, inclusive = false)
                    },
                    onReport = { graph.chatStore.report(id) },
                    onForward = { target -> if (target != id) openChat.value(target, null) },
                    // Set by a search hit in the chat list; absent from routes that do not declare it.
                    highlightMessageId = entry.arguments?.getString("highlight")?.takeIf { it.isNotBlank() },
                    opener = graph.attachmentOpener,
                )
            }
            composable(INFO, idArg) { entry ->
                val id = entry.arguments!!.getString("id")!!
                ChatInfoScreen(
                    graph.chatStore, id,
                    onBack = { nav.popBackStack() },
                    onCall = if (graph.callsAvailable) { video -> callVm.start(id, video) } else null,
                    onSearch = {},
                    onBlocked = { nav.popBackStack(MAIN, inclusive = false) },
                )
            }
        }
        CallOverlay(call?.phase, callVm)
        }
        }
    }
}

/** The ringing or active call over everything, also over the lock cover: it shows the caller's name and the controls only. */
@Composable
private fun CallOverlay(phase: CallPhase?, callVm: CallViewModel) {
    when (phase) {
        null -> {}
        CallPhase.Incoming -> IncomingCallScreen(callVm)
        else -> CallScreen(callVm)
    }
}

/** Sample data answers with a scripted fake (the first, automatic prompt is dismissed; Unlock passes); live uses the phone's own. */
@Composable
private fun defaultDeviceAuth(graph: AppGraph): DeviceAuth {
    val context = LocalContext.current
    return remember(graph, context) {
        if (graph.sampleData) SampleDeviceAuth(results = listOf(AuthResult.Cancelled)) else PlatformDeviceAuth(context)
    }
}
