package org.example.app.ui.nav

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.activity.OnBackPressedDispatcher
import kotlin.test.assertEquals
import org.example.app.MainActivity
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The real activity on sample data: one pane below 840 dp, the list beside the chat from 840 dp, state kept across rotation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w900dp-h600dp")
class TwoPaneTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun launch(lock: Boolean = false): ActivityScenario<MainActivity> {
        val s = ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SAMPLE_DATA, true).putExtra(MainActivity.EXTRA_APP_LOCK, lock),
        )
        scenario = s
        rule.waitForIdle()
        return s
    }

    @After fun close() {
        scenario?.close()
        // Sample drafts are shared by the whole test JVM: leave none behind.
        org.example.app.AppGraph(context, sampleData = true).chatStore.setDraft("chat-riya", null)
    }

    private fun count(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test fun aWideWindowShowsSelectAChatBesideTheList() {
        launch()
        rule.onNodeWithText("Riya").assertIsDisplayed()
        rule.onNodeWithText("Select a chat").assertIsDisplayed()
    }

    @Test fun aWideWindowHasARailAndNoBottomBar() {
        launch()
        // Four tabs, once each: the rail; a bottom bar would add a second set.
        for (tab in listOf("Contacts", "Calls", "Settings")) assertEquals(1, count(tab), tab)
        rule.onNode(androidx.compose.ui.test.isSelected() and hasText("Chats")).assertExists()
    }

    @Test fun openingAChatShowsItBesideTheListAndKeepsTheListVisible() {
        launch()
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        assertEquals(2, count(riyaText)) // the list row and the bubble
        assertEquals(0, count("Select a chat"))
        // The list is still there: another chat's row.
        rule.onNodeWithText("Arjun").assertIsDisplayed()
    }

    private fun selectedRows() = rule.onAllNodes(androidx.compose.ui.test.isSelected() and hasText("Riya") or
        (androidx.compose.ui.test.isSelected() and hasText("Arjun"))).fetchSemanticsNodes().size

    @Test fun theOpenChatsRowIsSelectedInTheListAndNoOtherIs() {
        launch()
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.isSelected() and hasText("Riya")).assertIsDisplayed()
        rule.onNode(androidx.compose.ui.test.isNotSelected() and hasText("Arjun")).assertIsDisplayed()
        assertEquals(1, selectedRows())
    }

    @Test fun noRowIsSelectedWithNoChatOpenOrInOnePane() {
        launch()
        assertEquals(0, selectedRows()) // nothing open yet
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        RuntimeEnvironment.setQualifiers("w411dp-h900dp")
        scenario!!.recreate()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.isSelected() and hasText("Riya")).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.isNotSelected() and hasText("Riya")).fetchSemanticsNodes().size)
        // Back to the list: the chat's row carries no selection state at all in one pane.
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Riya").assertIsDisplayed()
        assertEquals(0, selectedRows())
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.isNotSelected() and hasText("Riya")).fetchSemanticsNodes().size)
    }

    @Test fun aNarrowWindowIsUnchanged() {
        RuntimeEnvironment.setQualifiers("w411dp-h900dp")
        launch()
        assertEquals(0, count("Select a chat"))
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("See you at 6, call me when you leave").assertIsDisplayed()
        // One screen at a time: the list's other rows are not beside the chat.
        assertEquals(0, count("Arjun"))
    }

    private val riyaText = "See you at 6, call me when you leave"

    @Test fun theOpenChatAndTheComposerTextSurviveARotationOfATablet() {
        val s = launch()
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText("")).performTextInput("half a thought")
        rule.waitForIdle()
        RuntimeEnvironment.setQualifiers("w1280dp-h720dp-land")
        s.recreate()
        rule.waitForIdle()
        assertEquals(0, count("Select a chat"))
        assertEquals(1, count(riyaText)) // the bubble; the list row shows the draft instead
        rule.onNodeWithText("Draft: half a thought").assertIsDisplayed()
        rule.onNodeWithText("half a thought").assertIsDisplayed()
        rule.onNodeWithText("Arjun").assertIsDisplayed()
    }

    @Test fun theOpenChatAndTheComposerTextSurviveARotationOfAPhone() {
        RuntimeEnvironment.setQualifiers("w411dp-h900dp")
        val s = launch()
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText("")).performTextInput("half a thought")
        rule.waitForIdle()
        RuntimeEnvironment.setQualifiers("w700dp-h360dp-land") // still below 840 dp
        s.recreate()
        rule.waitForIdle()
        assertEquals(1, count(riyaText))
        rule.onNodeWithText("half a thought").assertIsDisplayed()
    }

    @Test fun theOpenChatFollowsAWindowAcrossTheThreshold() {
        val s = launch()
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        RuntimeEnvironment.setQualifiers("w600dp-h1000dp")
        s.recreate()
        rule.waitForIdle()
        assertEquals(1, count(riyaText)) // the chat on its own screen
        assertEquals(0, count("Arjun"))
        RuntimeEnvironment.setQualifiers("w900dp-h600dp")
        s.recreate()
        rule.waitForIdle()
        assertEquals(2, count(riyaText))
        rule.onNodeWithText("Arjun").assertIsDisplayed()
        // Back from the chat on its own screen would have left it: here it closes the pane.
        s.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Select a chat").assertIsDisplayed()
    }

    @Test fun backLeavesTheChatPaneBeforeTheApp() {
        val s = launch()
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        s.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Select a chat").assertIsDisplayed()
        rule.onNodeWithText("Arjun").assertIsDisplayed()
    }

    @Test fun theLockCoverHidesBothPanes() {
        launch(lock = true)
        rule.onNodeWithText("Unlock").assertIsDisplayed()
        assertEquals(0, count("Riya"))
        assertEquals(0, count("Select a chat"))
    }
}
