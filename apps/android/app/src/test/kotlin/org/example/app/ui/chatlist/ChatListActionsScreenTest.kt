package org.example.app.ui.chatlist

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.ChatListOrder
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import org.example.app.core.settings.UserSettings
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class ChatListActionsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val server = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    private val store = SampleChatStore(SampleData.make(Instant.now()))
    private var archiveOpened = 0

    private fun viewModel() =
        ChatListViewModel(store, server, kotlinx.coroutines.flow.MutableStateFlow(UserSettings()), NetworkType.Wifi)

    private fun launchList() {
        val vm = viewModel()
        rule.setContent {
            AppTheme { ChatListScreen(vm, onOpenChat = {}, onOpenRequests = {}, onNewChat = {}, onSearch = {}, onOpenArchive = { archiveOpened += 1 }) }
        }
    }

    private fun launchArchive() {
        val vm = viewModel()
        rule.setContent { AppTheme { ArchiveScreen(vm, onOpenChat = {}, onBack = {}) } }
    }

    private fun longPress(name: String) {
        rule.onNodeWithText(name).performTouchInput { longClick() }
        rule.waitForIdle()
    }

    private fun name(chatId: String) = store.contact(store.chat(chatId)!!.contactId)!!.displayName

    @Test
    fun longPressOpensTheMenuWithTheFourActions() {
        launchList()
        longPress("Sam")
        rule.onNodeWithText("Pin").assertIsDisplayed()
        rule.onNodeWithText("Mute").assertIsDisplayed()
        rule.onNodeWithText("Archive").assertIsDisplayed()
        rule.onNodeWithText("Mark as read").assertIsDisplayed()
    }

    @Test
    fun pinningFromTheMenuShowsThePinAndMovesTheRowFirst() {
        launchList()
        longPress("Sam")
        rule.onNodeWithText("Pin").performClick()
        rule.waitForIdle()
        assertNotNull(store.chat("chat-sam")!!.pinnedAt)
        rule.onNodeWithContentDescription("Pinned").assertIsDisplayed()
        assertEquals("chat-sam", store.chats.value.first().id)
        longPress("Sam")
        rule.onNodeWithText("Unpin").assertIsDisplayed()
        rule.onNodeWithText("Unpin").performClick()
        rule.waitForIdle()
        assertNull(store.chat("chat-sam")!!.pinnedAt)
        rule.onNodeWithContentDescription("Pinned").assertDoesNotExist()
    }

    @Test
    fun theSixthPinSaysSo() {
        val six = store.chats.value.take(6).map { it.id }
        six.take(ChatListOrder.MAX_PINS).forEach { assertTrue(store.setPinned(it, true)) }
        launchList()
        longPress(name(six[5]))
        rule.onNodeWithText("Pin").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("You can pin up to 5 chats.").assertIsDisplayed()
        assertNull(store.chat(six[5])!!.pinnedAt)
    }

    @Test
    fun muteShowsTheMutedIconAndTheMenuOffersUnmute() {
        launchList()
        longPress("Riya")
        rule.onNodeWithText("Mute").performClick()
        rule.waitForIdle()
        assertTrue(store.chat("chat-riya")!!.muted)
        rule.onNodeWithContentDescription("Muted").assertIsDisplayed()
        longPress("Riya")
        rule.onNodeWithText("Unmute").assertIsDisplayed()
        rule.onNodeWithText("Unmute").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Muted").assertDoesNotExist()
    }

    @Test
    fun markAsReadClearsTheUnreadCount() {
        launchList()
        assertTrue(store.chat("chat-riya")!!.unreadCount > 0)
        longPress("Riya")
        rule.onNodeWithText("Mark as read").performClick()
        rule.waitForIdle()
        assertEquals(0, store.chat("chat-riya")!!.unreadCount)
    }

    @Test
    fun archivingRemovesTheRowAndAddsTheArchivedRowThatOpensTheArchive() {
        launchList()
        rule.onNodeWithText("Archived").assertDoesNotExist()
        longPress("Sam")
        rule.onNodeWithText("Archive").performClick()
        rule.waitForIdle()
        assertTrue(store.chat("chat-sam")!!.archived)
        rule.onNodeWithText("Sam").assertDoesNotExist()
        rule.onNodeWithText("Archived").assertIsDisplayed()
        rule.onNodeWithText("Archived").assertTextContains("1", substring = true)
        rule.onNodeWithText("Archived").performClick()
        assertEquals(1, archiveOpened)
    }

    @Test
    fun theArchivedRowShowsTheUnreadCountOfArchivedChats() {
        store.setArchived("chat-riya", true) // Riya has unread messages
        launchList()
        rule.onNodeWithText("Archived").assertIsDisplayed()
        rule.onNodeWithContentDescription("1 unread").assertIsDisplayed()
    }

    @Test
    fun swipingFromTheEndArchivesAndFromTheStartPins() {
        launchList()
        rule.onNodeWithText("Sam").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertTrue(store.chat("chat-sam")!!.archived)
        rule.onNodeWithText("Arjun").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertNotNull(store.chat("chat-arjun")!!.pinnedAt)
    }

    @Test
    fun aDraftReplacesTheLastMessageInTheRow() {
        store.setDraft("chat-sam", "Remember the milk")
        launchList()
        rule.onNodeWithText("Draft:", substring = true).assertIsDisplayed()
        rule.onNode(hasText("Remember the milk", substring = true)).assertIsDisplayed()
        store.setDraft("chat-sam", null)
        rule.waitForIdle()
        rule.onAllNodesWithText("Draft:", substring = true).assertCountEquals(0)
    }

    @Test
    fun theArchiveListsArchivedChatsAndUnarchiveBringsThemBack() {
        store.setArchived("chat-sam", true)
        launchArchive()
        rule.onNodeWithText("Sam").assertIsDisplayed()
        rule.onNodeWithText("Riya").assertDoesNotExist()
        longPress("Sam")
        rule.onNodeWithText("Unarchive").performClick()
        rule.waitForIdle()
        assertTrue(!store.chat("chat-sam")!!.archived)
        rule.onNodeWithText("No archived chats").assertIsDisplayed()
    }

    @Test
    fun anEmptyArchiveSaysSo() {
        launchArchive()
        rule.onNodeWithText("No archived chats").assertIsDisplayed()
    }

    @Test
    fun swipingFromTheEndInTheArchiveUnarchives() {
        store.setArchived("chat-sam", true)
        launchArchive()
        rule.onNodeWithText("Sam").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertTrue(!store.chat("chat-sam")!!.archived)
    }
}
