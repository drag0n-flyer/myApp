package org.example.app.ui.chatinfo

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import org.example.app.MainActivity
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SampleChatStore
import org.example.app.ui.msg
import org.example.app.ui.storeWith
import org.example.app.ui.theme.AppTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Chat info > Disappearing messages (task P3), in sample mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class DisappearingMessagesTest {
    @get:Rule val rule = createComposeRule()

    private class Recording(val inner: SampleChatStore) : ChatStore by inner {
        val chosen = mutableListOf<Int>()
        override fun setTimer(chatId: String, seconds: Int) { chosen += seconds; inner.setTimer(chatId, seconds) }
    }

    @Test fun aBubbleUnderATimerShowsTheGlyphWithItsLabelAndOthersDoNot() {
        val timed = msg("1", "riya", MessageContent.Text("fades")).copy(expiresAt = java.time.Instant.now().plusSeconds(3_600))
        val plain = msg("2", "riya", MessageContent.Text("stays"))
        val vm = org.example.app.ui.chat.ChatViewModel(storeWith(listOf(timed, plain)), "chat-riya", org.example.app.core.calls.NetworkType.Wifi) { org.example.app.core.settings.AutoDownloadSettings() }
        rule.setContent { AppTheme { org.example.app.ui.chat.ChatScreen(vm, myId = "me", onBack = {}, onOpenInfo = {}, onCall = {}) } }
        rule.onAllNodesWithContentDescription("Disappearing message").assertCountEquals(1)
    }

    private fun launch(store: ChatStore) = rule.setContent {
        AppTheme { ChatInfoScreen(store, "chat-riya", onBack = {}, onCall = {}, onSearch = {}, onBlocked = {}) }
    }

    @Test fun theRowShowsOffAndOpensTheFourChoicesWithTheExplanation() {
        launch(storeWith(listOf(msg("1", "riya", MessageContent.Text("hi")))))
        rule.onNodeWithText("Disappearing messages").assertIsDisplayed()
        rule.onNodeWithText("Off").assertIsDisplayed()
        rule.onNodeWithText("Disappearing messages").performClick()
        for (choice in listOf("Off", "1 day", "7 days", "90 days")) rule.onAllNodesWithText(choice).onLast().assertIsDisplayed()
        rule.onNodeWithText("New messages disappear from this chat after the time you choose. People can still copy or photograph them.").assertIsDisplayed()
    }

    @Test fun choosingSevenDaysSetsTheTimerOnceAndTheRowShowsIt() {
        val store = Recording(storeWith(listOf(msg("1", "riya", MessageContent.Text("hi")))))
        launch(store)
        rule.onNodeWithText("Disappearing messages").performClick()
        rule.onNodeWithText("7 days").performClick()
        rule.waitForIdle()
        assertEquals(listOf(604_800), store.chosen)
        rule.onNodeWithText("7 days").assertIsDisplayed() // the row's value; the dialog is closed
        rule.onNodeWithText("90 days").assertDoesNotExist()
    }

    @Test fun theChoicesAreRadioButtonsWithTheCurrentOneSelected() {
        val store = storeWith(listOf(msg("1", "riya", MessageContent.Text("hi"))))
        store.setTimer("chat-riya", 86_400)
        launch(store)
        rule.onNodeWithText("Disappearing messages").performClick()
        rule.onNode(hasText("1 day") and isSelectable()).assertIsSelected()
        rule.onNode(hasText("7 days") and isSelectable()).assertIsNotSelected()
    }
}

/** The whole path on the real activity: set 1 day in chat info, the system line appears in the chat, the row says "1 day". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class DisappearingMessagesFlowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun close() { scenario?.close() }

    @Test fun oneDayInChatInfoShowsTheLineInTheChat() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SAMPLE_DATA, true))
        rule.waitForIdle()
        rule.onNodeWithText("Riya").performClick()
        rule.onNodeWithText("Riya").performClick() // the chat header opens chat info
        rule.onNodeWithText("Disappearing messages").performClick()
        rule.onNodeWithText("1 day").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("1 day").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("You set messages to disappear after 1 day").assertIsDisplayed()
    }
}
