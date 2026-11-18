package org.example.app.ui.chatlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
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
class NewGroupScreenTest {
    @get:Rule val rule = createComposeRule()
    private val store = SampleChatStore(SampleData.make(Instant.now()))
    private val server = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    private val created = mutableListOf<String>()

    private fun launch() = rule.setContent { AppTheme { NewGroupScreen(store, onCreated = { created += it }, onBack = {}) } }

    @Test
    fun theNewChatScreenOffersNewGroupOnlyWhenAskedTo() {
        var groups = 0
        rule.setContent { AppTheme { NewChatScreen(store, onChoose = {}, onAddContact = {}, onBack = {}, onNewGroup = { groups++ }) } }
        rule.onNodeWithText("New group").performClick()
        assertEquals(1, groups)
    }

    @Test
    fun theNextStepNeedsAtLeastTwoPeopleAndSaysSo() {
        launch()
        rule.onNodeWithText("Add people").assertIsDisplayed()
        rule.onNodeWithText("Choose at least 2 people").assertIsDisplayed()
        rule.onNodeWithText("Continue").assertIsNotEnabled()
        rule.onNodeWithText("Riya").performClick()
        rule.onNodeWithText("Continue").assertIsNotEnabled()
        rule.onNodeWithText("Choose at least 2 people").assertIsDisplayed()
        val second = store.contacts.value.map { it.displayName }.first { it != "Riya" }
        rule.onNodeWithText(second).performClick()
        rule.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun createAGroupOfTwoContactsAndItAppearsInTheChatListWithItsName() {
        launch()
        val names = store.contacts.value.take(2).map { it.displayName }
        names.forEach { rule.onNodeWithText(it).performClick() }
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Group name").assertIsDisplayed()
        rule.onNodeWithText("Create").assertIsNotEnabled()
        rule.onNodeWithText("Group name").performTextInput("Book club")
        rule.onNodeWithText("Create").assertIsEnabled()
        rule.onNodeWithText("Create").performClick()
        rule.waitUntil(5_000) { created.isNotEmpty() }
        assertTrue(store.chats.value.any { it.title == "Book club" && it.id == created.single() })

    }

    @Test
    fun aCreatedGroupShowsItsNameInTheChatList() {
        kotlinx.coroutines.runBlocking { store.createGroup(store.contacts.value.take(2).map { it.id }, "Book club", null) }
        val vm = ChatListViewModel(store, server, kotlinx.coroutines.flow.MutableStateFlow(UserSettings()), NetworkType.Wifi)
        rule.setContent {
            AppTheme { ChatListScreen(vm, onOpenChat = {}, onOpenRequests = {}, onNewChat = {}, onSearch = {}, onOpenArchive = {}) }
        }
        rule.onNodeWithText("Book club").assertIsDisplayed()
    }

    @Test
    fun theBackControlHasALabel() {
        launch()
        rule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }
}
