package org.example.app.ui.chatinfo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertTrue
import org.example.app.core.chats.ChatEncryption
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.MessageContent
import org.example.app.ui.msg
import org.example.app.ui.storeWith
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class ChatInfoScreenTest {
    @get:Rule val rule = createComposeRule()

    private val messages = listOf(msg("1", "riya", MessageContent.Text("hi")))
    private var blocked = 0

    private fun launch(store: ChatStore) = rule.setContent {
        AppTheme { ChatInfoScreen(store, "chat-riya", onBack = {}, onCall = {}, onSearch = {}, onBlocked = { blocked++ }) }
    }

    @Test
    fun endToEndChatSaysSo() {
        launch(storeWith(messages, ChatEncryption.EndToEnd))
        rule.onNodeWithText("End-to-end encrypted").assertIsDisplayed()
        rule.onNodeWithText("Not end-to-end encrypted: the server admin can read this chat").assertDoesNotExist()
    }

    @Test
    fun serverReadableChatSaysSo() {
        launch(storeWith(messages, ChatEncryption.ServerReadable))
        rule.onNodeWithText("Not end-to-end encrypted: the server admin can read this chat").assertIsDisplayed()
        rule.onNodeWithText("End-to-end encrypted").assertDoesNotExist()
    }

    @Test
    fun safetyNumbersHiddenWhileKeysAreNotReal() {
        val sample = storeWith(messages)
        val noKeys = object : ChatStore by sample { override val supportsSafetyNumbers = false }
        launch(noKeys)
        rule.onNodeWithText("End-to-end encrypted").assertIsDisplayed()
        rule.onNodeWithText("Verify").assertDoesNotExist()
        rule.onNodeWithText("Verified").assertDoesNotExist()
    }

    @Test
    fun unreadableMembersHideTheNumberAndSaySo() {
        val sample = storeWith(messages)
        sample.setVerified(true, "chat-riya")
        val unreadable = object : ChatStore by sample { override fun membersUnreadable(chatId: String) = true }
        launch(unreadable)
        rule.onNodeWithText("Can't check the devices in this chat right now, so the safety number is hidden.").assertIsDisplayed()
        rule.onNodeWithText("Verify").assertDoesNotExist()
        rule.onNodeWithText("Verified").assertDoesNotExist()
    }

    @Test
    fun verifyAppearsWhenKeysAreReal_andMarksVerified() {
        val store = storeWith(messages)
        launch(store)
        rule.onNodeWithText("Verified").assertDoesNotExist()
        rule.onNodeWithText("Verify").performClick()
        rule.onNodeWithText("Mark as verified").assertIsDisplayed()
        rule.onNodeWithText("Mark as verified").performClick()
        rule.waitForIdle()
        assertTrue(store.safetyNumber("chat-riya")!!.isVerified)
    }

    @Test
    fun mismatchReplacesTheEncryptionLine() {
        launch(storeWith(messages, modeMismatch = true))
        rule.onNodeWithText("End-to-end encrypted").assertDoesNotExist()
        rule.onNodeWithText("The server's settings for this chat don't match the ones it started with. Sending is paused to keep your messages private.")
            .assertIsDisplayed()
    }

    @Test
    fun blockAsksFirst() {
        val store = storeWith(messages)
        launch(store)
        rule.onNodeWithText("Block Riya").performClick()
        rule.onNodeWithText("Block this person?").assertIsDisplayed()
        assertTrue(blocked == 0)
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(store.blockedIds.value.isEmpty())
    }
}
