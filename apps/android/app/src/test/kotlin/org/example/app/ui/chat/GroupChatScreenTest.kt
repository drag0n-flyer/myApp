package org.example.app.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import java.time.Instant
import kotlin.test.assertEquals
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.Chat
import org.example.app.core.chats.ChatEncryption
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sample mode: the sample group shows who wrote what, collapses a blocked sender, and Message info lists both members. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class GroupChatScreenTest {
    @get:Rule val rule = createComposeRule()

    private val calls = mutableListOf<Boolean>()

    private fun launch(store: ChatStore, chatId: String) {
        val vm = ChatViewModel(store, chatId, NetworkType.Wifi) { AutoDownloadSettings() }
        rule.setContent {
            AppTheme { ChatScreen(vm, myId = "me", onBack = {}, onOpenInfo = {}, onCall = { calls += it }) }
        }
    }

    private fun sample(): Pair<SampleChatStore, String> {
        val store = SampleChatStore()
        return store to store.addSampleGroup()
    }

    @Test
    fun theSampleGroupShowsTwoSendersNamesOncePerRun() {
        val (store, id) = sample()
        launch(store, id)
        // Riya wrote two messages in a row: one name line. Arjun wrote one.
        assertEquals(1, rule.onAllNodesWithText("Riya").fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithText("Arjun").fetchSemanticsNodes().size)
        rule.onNodeWithText("Who is bringing snacks on Saturday?").assertIsDisplayed()
        rule.onNodeWithText("Count me in for the snacks").assertIsDisplayed()
        // My own messages carry no name.
        assertEquals(0, rule.onAllNodesWithText("Your Name").fetchSemanticsNodes().size)
    }

    @Test
    fun aGroupHasNoCallButtons() {
        val (store, id) = sample()
        launch(store, id)
        rule.onNodeWithContentDescription("Audio call").assertDoesNotExist()
        rule.onNodeWithContentDescription("Video call").assertDoesNotExist()
    }

    @Test
    fun messageInfoListsBothMembersWithReadAndDelivered() {
        val (store, id) = sample()
        launch(store, id)
        rule.onNodeWithText("I can bring the drinks").performTouchInput { longClick() }
        rule.onNodeWithText("Message info").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Read by").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Read by").assertIsDisplayed()
        rule.onNodeWithText("Delivered to").assertIsDisplayed()
        // Riya read it, Arjun only got it: each name now shows in the sheet as well as above their bubbles.
        assertEquals(2, rule.onAllNodesWithText("Riya").fetchSemanticsNodes().size)
        assertEquals(2, rule.onAllNodesWithText("Arjun").fetchSemanticsNodes().size)
    }

    @Test
    fun aMessageFromSomeoneElseHasNoMessageInfo() {
        val (store, id) = sample()
        launch(store, id)
        rule.onNodeWithText("Count me in for the snacks").performTouchInput { longClick() }
        rule.onNodeWithText("Message info").assertDoesNotExist()
    }

    private class Blocking(val inner: SampleChatStore, blocked: Set<String>) : ChatStore by inner {
        override val blockedIds = kotlinx.coroutines.flow.MutableStateFlow(blocked)
    }

    @Test
    fun aBlockedMembersMessageIsCollapsedUntilShown() {
        val (inner, id) = sample()
        launch(Blocking(inner, setOf("arjun")), id)
        rule.onNodeWithText("Count me in for the snacks").assertDoesNotExist()
        rule.onNodeWithText("Message from a blocked contact").assertIsDisplayed()
        rule.onNodeWithContentDescription("Show message from a blocked contact").performClick()
        rule.onNodeWithText("Count me in for the snacks").assertIsDisplayed()
    }

    @Test
    fun aOneToOneChatShowsNoSenderNames() {
        val data = SampleData.make(Instant.now())
        val chat = Chat(
            "chat-riya", "riya", ChatEncryption.EndToEnd, emptySet(), 0,
            listOf(Message("1", "riya", Instant.now(), MessageContent.Text("hello there"), DeliveryStatus.Read, null, emptyList())), false,
        )
        launch(SampleChatStore(data.copy(chats = listOf(chat))), "chat-riya")
        rule.onNodeWithText("hello there").assertIsDisplayed()
        // The header names Riya once; no sender line adds a second.
        assertEquals(1, rule.onAllNodesWithText("Riya").fetchSemanticsNodes().size)
    }
}
