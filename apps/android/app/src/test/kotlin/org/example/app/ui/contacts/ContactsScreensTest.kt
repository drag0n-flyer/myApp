package org.example.app.ui.contacts

import java.time.Instant
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.ui.allLabels
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class ContactsScreensTest {
    @get:Rule val rule = createComposeRule()
    private fun store() = SampleChatStore(SampleData.make(Instant.now()))

    @Test
    fun contactsAreGroupedByLetterWithInviteAndAdd() {
        val opened = mutableListOf<String>()
        rule.setContent {
            AppTheme { ContactsScreen(store(), inviteUrl = "https://h/invite/x", onOpenChat = { opened += it }, onAddContact = {}, onInvite = {}) }
        }
        rule.onNodeWithText("Invite people").assertIsDisplayed()
        rule.onNodeWithText("Add contact").assertIsDisplayed()
        rule.onNodeWithText("Riya").performClick()
        assertEquals(1, opened.size)
    }

    @Test
    fun addContactFindsAKnownUsernameAndReportsAnUnknownOne() {
        var added = 0
        rule.setContent { AppTheme { AddContactScreen(store(), onBack = {}, onAdded = { added++ }) } }
        rule.onNodeWithText("Username").performTextInput("nobody")
        rule.onNodeWithText("Add").performClick()
        rule.waitUntil(5_000) { rule.allLabels().any { it.startsWith("No one with that username") } }
        assertEquals(0, added)
    }

    @Test
    fun addContactAcceptsAtSign() {
        var added = 0
        rule.setContent { AppTheme { AddContactScreen(store(), onBack = {}, onAdded = { added++ }) } }
        rule.onNodeWithText("Username").performTextInput("@neha")
        rule.onNodeWithText("Add").performClick()
        rule.waitUntil(5_000) { added == 1 }
    }

    @Test
    fun requestsListStrangersWithAFooter() {
        val opened = mutableListOf<String>()
        rule.setContent { AppTheme { MessageRequestsScreen(store(), onOpenChat = { opened += it }, onBack = {}) } }
        rule.onNodeWithText("Message requests").assertIsDisplayed()
        rule.onNodeWithText("People not in your contacts land here. They can't ring you until you accept.").assertIsDisplayed()
        rule.onNodeWithText("Unknown Caller").performClick()
        assertEquals(listOf("chat-stranger1"), opened)
    }

    @Test
    fun emptyRequests() {
        val empty = SampleChatStore(SampleData.make(Instant.now()).copy(requestSenders = emptyList(), chats = emptyList()))
        rule.setContent { AppTheme { MessageRequestsScreen(empty, onOpenChat = {}, onBack = {}) } }
        rule.onNodeWithText("No message requests").assertIsDisplayed()
    }
}
