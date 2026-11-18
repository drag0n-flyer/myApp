package org.example.app.ui.lock

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.example.app.AppGraph
import org.example.app.AppRoot
import org.example.app.ui.allLabels
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app lock over the two-pane layout: the cover covers both panes, and a held notification tap opens beside the list after the unlock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w900dp-h600dp")
class TwoPaneLockTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val graph = AppGraph(context, sampleData = true)
    private val auth = SampleDeviceAuth(results = listOf(AuthResult.Cancelled))

    private fun count(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun show() {
        graph.settingsStore.update { it.copy(appLock = true, lockAfterSeconds = 0) }
        rule.setContent { AppRoot(graph, startWithOnboarding = false, deviceAuth = auth) }
        rule.waitForIdle()
    }

    @Test fun theCoverIsAloneOnAWideWindow() {
        show()
        assertEquals(setOf("[App]", "Unlock", "Unlock [App]"), rule.allLabels().toSet())
        assertEquals(0, count("Select a chat"))
    }

    @Test fun aHeldChatOpensBesideTheListAfterTheUnlockAndNeverWhileCovered() {
        show()
        graph.requestedChat.value = "chat-riya"
        rule.waitForIdle()
        assertEquals("chat-riya", graph.requestedChat.value)
        assertEquals(0, count("See you at 6, call me when you leave"))
        assertEquals(0, count("Select a chat"))
        rule.onNodeWithText("Unlock").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Message").assertIsDisplayed()
        rule.onNodeWithText("Arjun").assertIsDisplayed() // the list is beside it
        assertNull(graph.requestedChat.value)
    }

    @Test fun anOpenChatIsNotShownWhileTheCoverIsUpAndReturnsAfterTheUnlock() {
        show()
        rule.onNodeWithText("Unlock").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        auth.answerWith(AuthResult.Cancelled)
        rule.runOnUiThread { graph.appLock.onStop(); graph.appLock.onStart() }
        rule.waitForIdle()
        assertEquals(0, count("Message"))
        assertEquals(0, count("Arjun"))
        rule.onNodeWithText("Unlock").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Message").assertIsDisplayed()
    }
}
