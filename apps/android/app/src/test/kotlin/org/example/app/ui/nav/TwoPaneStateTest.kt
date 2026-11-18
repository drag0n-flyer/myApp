package org.example.app.ui.nav

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import org.example.app.AppGraph
import org.example.app.MainActivity
import org.example.app.core.chats.MessageContent
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** State handling of the two-pane layout: rotation while locked, the pane's view models, restored ids, chat info's Search. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w900dp-h600dp")
class TwoPaneStateTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val riyaText = "See you at 6, call me when you leave"

    private fun launch(lock: Boolean): ActivityScenario<MainActivity> {
        val s = ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SAMPLE_DATA, true).putExtra(MainActivity.EXTRA_APP_LOCK, lock),
        )
        scenario = s
        rule.waitForIdle()
        return s
    }

    @After fun close() {
        scenario?.close()
        AppGraph(context, sampleData = true).chatStore.setDraft("chat-riya", null)
    }

    private fun count(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun graphOf(s: ActivityScenario<MainActivity>): AppGraph {
        var g: AppGraph? = null
        s.onActivity { a -> g = MainActivity::class.java.getDeclaredField("graph").also { it.isAccessible = true }.get(a) as AppGraph }
        return g!!
    }

    private fun unlock() {
        rule.onNodeWithText("Unlock").performClick()
        rule.waitForIdle()
    }

    @Test fun aRotationWhileLockedToANarrowWindowDoesNotCrashAndTheChatOpensAfterTheUnlock() {
        val s = launch(lock = true)
        unlock()
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        RuntimeEnvironment.setQualifiers("w411dp-h900dp")
        s.recreate()
        rule.waitForIdle()
        rule.onNodeWithText("Unlock").assertIsDisplayed()
        assertEquals(0, count("Riya"))
        unlock()
        assertEquals(1, count(riyaText)) // the chat on its own screen
        assertEquals(0, count("Arjun"))
    }

    @Test fun aRotationWhileLockedToAWideWindowCarriesTheChatIntoThePaneAfterTheUnlock() {
        RuntimeEnvironment.setQualifiers("w411dp-h900dp")
        val s = launch(lock = true)
        unlock()
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        assertEquals(1, count(riyaText)) // opened on its own screen before the rotation
        RuntimeEnvironment.setQualifiers("w900dp-h600dp")
        s.recreate()
        rule.waitForIdle()
        rule.onNodeWithText("Unlock").assertIsDisplayed()
        unlock()
        assertEquals(0, count("Select a chat"))
        rule.onNodeWithText("Arjun").assertIsDisplayed() // the list
        assertEquals(1, count("Message")) // the composer of the open chat, beside the list
    }

    @Test fun narrowToWideWithoutALockCarriesTheChat() {
        RuntimeEnvironment.setQualifiers("w411dp-h900dp")
        val s = launch(lock = false)
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        RuntimeEnvironment.setQualifiers("w900dp-h600dp")
        s.recreate()
        rule.waitForIdle()
        assertEquals(0, count("Select a chat"))
    }

    @Test fun narrowLockedRecreateKeepsTheChat() {
        RuntimeEnvironment.setQualifiers("w411dp-h900dp")
        val s = launch(lock = true)
        unlock()
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        s.recreate()
        rule.waitForIdle()
        unlock()
        assertEquals(1, count(riyaText))
    }

    @Test fun reopeningAChatInThePaneMarksItReadAndPlacesTheUnreadLineAtTheNewMessage() {
        val s = launch(lock = false)
        val graph = graphOf(s)
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Arjun").performClick()
        rule.waitForIdle()
        // Only the chat in the pane has a view model.
        val pane = s.let { var p: PaneChats? = null; it.onActivity { a -> p = paneOf(a, graph) }; p!! }
        assertEquals(1, pane.alive)
        graph.chatStore.receive(MessageContent.Text("fresh news"), graph.chatStore.contact("riya")!!)
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodesWithContentDescription("1 unread").fetchSemanticsNodes().size)
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithContentDescription("1 unread").fetchSemanticsNodes().size)
        rule.onNodeWithText("Unread messages").assertIsDisplayed()
        s.onActivity { a -> assertEquals(1, paneOf(a, graph).alive) }
        assertEquals(0, graph.chatStore.chat("chat-riya")!!.unreadCount)
    }

    @Test fun aPaneChatThatNoLongerExistsShowsSelectAChat() {
        val s = launch(lock = false)
        val graph = graphOf(s)
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        assertEquals(0, count("Select a chat"))
        graph.chatStore.block("riya") // the chat is gone from the store
        rule.waitForIdle()
        rule.onNodeWithText("Select a chat").assertIsDisplayed()
    }

    @Test fun chatInfoSearchOpensTheSearchFieldInThePane() {
        launch(lock = false)
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Riya").onLast().performClick() // the title opens chat info
        rule.waitForIdle()
        rule.onAllNodesWithText("Search").onLast().performClick()
        rule.waitForIdle()
        // Back on the chat with its search field open (a field that takes text, with the search hint).
        rule.onNode(hasSetTextAction() and hasText("")).assertIsDisplayed()
        assertEquals(0, count("Select a chat"))
    }

    private fun paneOf(activity: MainActivity, graph: AppGraph): PaneChats =
        ViewModelProvider(activity)[PaneChats.key(graph.sessionEnds.value), PaneChats::class.java]
}
