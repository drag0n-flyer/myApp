package org.example.app.ui.chatlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.Instant
import kotlin.test.assertEquals
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class NewChatScreenTest {
    @get:Rule val rule = createComposeRule()
    private val chosen = mutableListOf<String>()
    private var adds = 0

    private fun launch(store: SampleChatStore) = rule.setContent {
        AppTheme { NewChatScreen(store, onChoose = { chosen += it }, onAddContact = { adds++ }, onBack = {}) }
    }

    @Test
    fun choosingAContactReportsIt() {
        launch(SampleChatStore(SampleData.make(Instant.now())))
        rule.onNodeWithText("Riya").performClick()
        assertEquals(listOf("riya"), chosen)
    }

    @Test
    fun hasAnAddContactRow() {
        launch(SampleChatStore(SampleData.make(Instant.now())))
        rule.onNodeWithText("Add contact").performClick()
        assertEquals(1, adds)
    }

    @Test
    fun withNoContactsItSaysSoAndStillOffersAddContact() {
        launch(SampleChatStore(SampleData.make(Instant.now()).copy(contacts = emptyList())))
        rule.onNodeWithText("No contacts yet").assertIsDisplayed()
        rule.onNodeWithText("Add contact").assertIsDisplayed()
    }
}
