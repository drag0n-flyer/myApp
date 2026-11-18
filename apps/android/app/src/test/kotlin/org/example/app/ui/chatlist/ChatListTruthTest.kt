package org.example.app.ui.chatlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.Instant
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.example.app.core.account.ConnectionStatus
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import org.example.app.core.settings.UserSettings
import org.example.app.ui.search.SearchScreen
import org.example.app.ui.search.SearchViewModel
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class ChatListTruthTest {
    @get:Rule val rule = createComposeRule()

    private val server = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    private val status = MutableStateFlow(ConnectionStatus.Connected)
    private var newChats = 0
    private var searches = 0

    private fun launch(store: SampleChatStore) {
        val vm = ChatListViewModel(store, server, MutableStateFlow(UserSettings()), NetworkType.Wifi, status)
        rule.setContent {
            AppTheme { ChatListScreen(vm, onOpenChat = {}, onOpenRequests = {}, onNewChat = { newChats++ }, onSearch = { searches++ }) }
        }
    }

    private fun full() = SampleChatStore(SampleData.make(Instant.now()))
    private fun empty() = SampleChatStore(SampleData.make(Instant.now()).let { it.copy(chats = emptyList()) })

    @Test
    fun stripShowsTheHostWhenConnected() {
        launch(full())
        rule.onNodeWithText("home.example.org").assertIsDisplayed()
        rule.onNodeWithContentDescription("Connected to home.example.org").assertIsDisplayed()
    }

    @Test
    fun stripFollowsTheConnection() {
        launch(full())
        status.value = ConnectionStatus.Connecting
        rule.waitForIdle()
        rule.onNodeWithText("Connecting…").assertIsDisplayed()
        rule.onNodeWithText("home.example.org").assertDoesNotExist()
        status.value = ConnectionStatus.Offline
        rule.waitForIdle()
        rule.onNodeWithText("No connection").assertIsDisplayed()
        status.value = ConnectionStatus.Connected
        rule.waitForIdle()
        rule.onNodeWithText("home.example.org").assertIsDisplayed()
    }

    @Test
    fun emptyListHasANewChatButton() {
        launch(empty())
        rule.onNodeWithText("No chats yet").assertIsDisplayed()
        rule.onNodeWithText("New chat").performClick()
        assertEquals(1, newChats)
    }

    @Test
    fun searchActionOpensSearch() {
        launch(full())
        rule.onNodeWithContentDescription("Search").performClick()
        assertEquals(1, searches)
    }

    // ---- the search screen

    @Test
    fun searchShowsAContactByNameAndOpensItsChat() {
        val opened = mutableListOf<String>()
        val vm = SearchViewModel(full(), kotlinx.coroutines.Dispatchers.Unconfined, debounceMs = 0)
        rule.setContent {
            AppTheme { SearchScreen(vm, onOpenContact = { opened += it }, onOpenMessage = { _, _ -> }, onBack = {}) }
        }
        vm.setQuery("riya")
        rule.waitForIdle()
        rule.onNodeWithText("Riya").assertIsDisplayed().performClick()
        assertEquals(listOf("riya"), opened)
    }

    @Test
    fun searchOpensAMessageHitAtItsMessage() {
        val opened = mutableListOf<Pair<String, String>>()
        val vm = SearchViewModel(full(), kotlinx.coroutines.Dispatchers.Unconfined, debounceMs = 0)
        rule.setContent {
            AppTheme { SearchScreen(vm, onOpenContact = {}, onOpenMessage = { c, m -> opened += c to m }, onBack = {}) }
        }
        vm.setQuery("coming tonight")
        rule.waitForIdle()
        rule.onNodeWithText("Are you coming tonight?").performClick()
        assertEquals("chat-riya", opened.single().first)
    }

    @Test
    fun searchSaysNoResults() {
        val vm = SearchViewModel(full(), kotlinx.coroutines.Dispatchers.Unconfined, debounceMs = 0)
        rule.setContent { AppTheme { SearchScreen(vm, onOpenContact = {}, onOpenMessage = { _, _ -> }, onBack = {}) } }
        vm.setQuery("zzzzqqq")
        rule.waitForIdle()
        rule.onNodeWithText("No results").assertIsDisplayed()
    }
}
