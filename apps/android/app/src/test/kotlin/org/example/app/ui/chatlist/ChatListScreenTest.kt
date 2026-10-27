package org.example.app.ui.chatlist

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.Contact
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.core.settings.CallSettings
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import org.example.app.ui.allLabels
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class ChatListScreenTest {
    @get:Rule val rule = createComposeRule()

    private val server = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    private val opened = mutableListOf<String>()

    private fun launch(store: SampleChatStore, network: NetworkType = NetworkType.Wifi) {
        val vm = ChatListViewModel(store, server, CallSettings(), network)
        rule.setContent {
            AppTheme { ChatListScreen(vm, onOpenChat = { opened += it }, onOpenRequests = {}, onNewChat = {}) }
        }
    }

    private fun store() = SampleChatStore(SampleData.make(Instant.now()))

    @Test
    fun folderChipsFilterTheList() {
        launch(store())
        rule.onNodeWithText("All").assertIsSelected()
        rule.onNodeWithText("Sam").assertIsDisplayed()
        rule.onNodeWithText("Riya").assertIsDisplayed()

        rule.onNodeWithText("Work").performClick()
        rule.onNodeWithText("Work").assertIsSelected()
        rule.onNodeWithText("Sam").assertIsDisplayed()
        rule.onNodeWithText("Riya").assertDoesNotExist()

        rule.onNodeWithText("Personal").performClick()
        rule.onNodeWithText("Riya").assertIsDisplayed()
        rule.onNodeWithText("Sam").assertDoesNotExist()

        rule.onNodeWithText("Unread 1").performClick()
        rule.onNodeWithText("Riya").assertIsDisplayed()
        rule.onNodeWithText("Arjun").assertDoesNotExist()

        rule.onNodeWithText("All").performClick()
        rule.onNodeWithText("Sam").assertIsDisplayed()
    }

    @Test
    fun requestsRowSitsAtTopWithCount() {
        launch(store())
        val row = rule.onNodeWithText("Message requests · 1")
        row.assertIsDisplayed()
        val rowTop = row.getUnclippedBoundsInRoot().top
        val firstChatTop = rule.onNodeWithText("Riya").getUnclippedBoundsInRoot().top
        assertTrue(rowTop < firstChatTop, "requests row must be above the first chat")
    }

    @Test
    fun requestsNeverCountAsUnread() {
        val s = store()
        launch(s)
        s.receive(MessageContent.Text("hello"), Contact("new1", "New Person", "new1"))
        rule.waitForIdle()
        rule.onNodeWithText("Message requests · 2").assertIsDisplayed()
        // Only Riya has unread messages among chats; the request chats are not counted.
        rule.onNodeWithText("Unread 1").assertIsDisplayed()
        rule.onNodeWithText("New Person").assertDoesNotExist()
    }

    @Test
    fun requestsRowHiddenWhenThereAreNone() {
        val s = store()
        launch(s)
        s.accept("chat-stranger1")
        rule.waitForIdle()
        rule.onNodeWithText("Message requests · 1").assertDoesNotExist()
        rule.onNodeWithText("Unknown Caller").assertIsDisplayed()
    }

    @Test
    fun statusStripShowsServerAndMode() {
        launch(store(), NetworkType.Mobile)
        rule.onNodeWithText("home.example.org").assertIsDisplayed()
        rule.onNodeWithText("· Data saver").assertIsDisplayed()
    }

    @Test
    fun tappingARowOpensTheChat() {
        launch(store())
        rule.onNodeWithText("Riya").performClick()
        assertEquals(listOf("chat-riya"), opened)
    }

    @Test
    fun noEncryptionWordsOnTheList() {
        launch(store())
        val offenders = rule.allLabels().filter { it.contains("encrypt", ignoreCase = true) }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun everyClickableHasALabel() {
        launch(store())
        val unlabeled = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().isEmpty() &&
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().isEmpty()
        }
        assertTrue(unlabeled.isEmpty(), "unlabelled clickable: $unlabeled")
    }
}
