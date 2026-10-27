package org.example.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
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
import org.example.app.ui.theme.AppTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

private const val MAIN = "main"
private const val CHAT = "chat/{id}"
private const val INFO = "chatinfo/{id}"

/** A factory for a view model built from the graph; the key keeps one instance per chat. */
private inline fun <reified T : ViewModel> factory(crossinline make: () -> T) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
}

@Composable
fun AppRoot(graph: AppGraph) {
    AppTheme {
        val nav = rememberNavController()
        val idArg = listOf(navArgument("id") { type = NavType.StringType })
        val context = LocalContext.current
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val callVm = viewModel<CallViewModel>(
            factory = factory {
                CallViewModel(
                    graph.callEngine, graph.chatStore, graph.serverPolicy, graph.userSettings.call,
                    simulator = graph.callEngine as? SimulatedCallEngine, showSimulator = debuggable,
                )
            },
        )
        val call by callVm.call.collectAsState()
        Box {
        NavHost(nav, startDestination = MAIN) {
            composable(MAIN) {
                MainTabs { tab ->
                    if (tab == MainTab.Chats) {
                        val vm = viewModel<ChatListViewModel>(
                            factory = factory { ChatListViewModel(graph.chatStore, graph.serverPolicy, graph.userSettings.call, graph.network) },
                        )
                        ChatListScreen(vm, onOpenChat = { nav.navigate("chat/$it") }, onOpenRequests = {}, onNewChat = {})
                    } else if (tab == MainTab.Calls) {
                        val vm = viewModel<CallHistoryViewModel>(factory = factory { CallHistoryViewModel(graph.chatStore) })
                        CallHistoryScreen(vm, onCall = callVm::start)
                    } else {
                        TabPlaceholder(tab)
                    }
                }
            }
            composable(CHAT, idArg) { entry ->
                val id = entry.arguments!!.getString("id")!!
                val vm = viewModel<ChatViewModel>(
                    key = "chat-$id",
                    factory = factory { ChatViewModel(graph.chatStore, id, graph.network, graph.userSettings.autoDownload) },
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
