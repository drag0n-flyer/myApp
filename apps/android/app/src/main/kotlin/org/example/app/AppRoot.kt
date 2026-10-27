package org.example.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
import org.example.app.ui.onboarding.rememberLinkApprovalConfirm
import org.example.app.ui.theme.AppTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

private const val MAIN = "main"
private const val CHAT = "chat/{id}"
private const val INFO = "chatinfo/{id}"
private const val ONBOARDING = "onboarding"
/** The approving phone scans a new device's code (opened from Linked devices). */
const val LINK_SCAN = "linkscan"

/** A factory for a view model built from the graph; the key keeps one instance per chat. */
private inline fun <reified T : ViewModel> factory(crossinline make: () -> T) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
}

@Composable
fun AppRoot(
    graph: AppGraph,
    // Sample mode goes straight to the chats; the live build starts with onboarding until signed in.
    startWithOnboarding: Boolean = !graph.sampleData && !graph.accountService.isSignedIn,
) {
    AppTheme {
        val nav = rememberNavController()
        val idArg = listOf(navArgument("id") { type = NavType.StringType })
        NavHost(nav, startDestination = if (startWithOnboarding) ONBOARDING else MAIN) {
            composable(ONBOARDING) {
                val vm = viewModel<OnboardingViewModel>(factory = factory { OnboardingViewModel(graph.accountService) })
                OnboardingHost(vm, onFinished = { nav.navigate(MAIN) { popUpTo(ONBOARDING) { inclusive = true } } })
            }
            composable(LINK_SCAN) {
                val confirm = rememberLinkApprovalConfirm()
                val vm = viewModel<LinkApprovalViewModel>(factory = factory { LinkApprovalViewModel(graph.accountService, confirm) })
                LinkApprovalFlow(vm, onDone = { nav.popBackStack() })
            }
            composable(MAIN) {
                MainTabs { tab ->
                    if (tab == MainTab.Chats) {
                        val vm = viewModel<ChatListViewModel>(
                            factory = factory { ChatListViewModel(graph.chatStore, graph.serverPolicy, graph.userSettings.call, graph.network) },
                        )
                        ChatListScreen(vm, onOpenChat = { nav.navigate("chat/$it") }, onOpenRequests = {}, onNewChat = {})
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
                    onCall = {},
                )
            }
            composable(INFO, idArg) { entry ->
                val id = entry.arguments!!.getString("id")!!
                ChatInfoScreen(
                    graph.chatStore, id,
                    onBack = { nav.popBackStack() },
                    onCall = {},
                    onSearch = {},
                    onBlocked = { nav.popBackStack(MAIN, inclusive = false) },
                )
            }
        }
    }
}
