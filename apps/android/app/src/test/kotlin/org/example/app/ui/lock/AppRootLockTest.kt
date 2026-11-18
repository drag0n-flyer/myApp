package org.example.app.ui.lock

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.app.AppGraph
import org.example.app.AppRoot
import org.example.app.core.calls.SimulatedCallEngine
import org.example.app.ui.allLabels
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The lock gate in the real `AppRoot` on sample data: nothing behind the cover is composed, and a held request is followed once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class AppRootLockTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val graph = AppGraph(context, sampleData = true)

    // The first request (the automatic prompt) is dismissed; later ones pass.
    private val auth = SampleDeviceAuth(results = listOf(AuthResult.Cancelled))

    private fun show(lockOn: Boolean, seconds: Int = 60) {
        graph.settingsStore.update { it.copy(appLock = lockOn, lockAfterSeconds = seconds) }
        rule.setContent { AppRoot(graph, startWithOnboarding = false, deviceAuth = auth) }
        rule.waitForIdle()
    }

    @Test fun withTheLockOffTheChatsShowAndThereIsNoCover() {
        show(lockOn = false)
        rule.onNodeWithText("Riya").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount("Unlock"))
    }

    @Test fun lockedRootComposesOnlyTheCover() {
        show(lockOn = true)
        rule.onNodeWithText("Unlock").assertIsDisplayed()
        rule.onNodeWithContentDescription("Unlock [App]").assertIsDisplayed()
        // Every label on screen: the app name and the button, nothing from the chats, the tabs or the settings.
        assertEquals(setOf("[App]", "Unlock", "Unlock [App]"), rule.allLabels().toSet())
        assertEquals(0, rule.onAllNodesWithTextCount("Riya"))
    }

    @Test fun theAutomaticPromptOpensOnceAndTheButtonAsksAgain() {
        show(lockOn = true)
        assertEquals(1, auth.requests)
        rule.onNodeWithText("Unlock").performClick()
        rule.waitForIdle()
        assertEquals(2, auth.requests)
        rule.onNodeWithText("Riya").assertIsDisplayed()
    }

    @Test fun cancellingTheSheetLeavesTheCover() {
        auth.answerWith(AuthResult.Cancelled, AuthResult.Cancelled)
        show(lockOn = true)
        rule.onNodeWithText("Unlock").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount("Riya"))
    }

    @Test fun aChatRequestedWhileLockedIsHeldAndFollowedOnceAfterTheUnlock() {
        show(lockOn = true, seconds = 0)
        graph.requestedChat.value = "chat-riya"
        rule.waitForIdle()
        assertEquals("chat-riya", graph.requestedChat.value) // held
        assertEquals(0, rule.onAllNodesWithTextCount("See you at 6, call me when you leave"))
        rule.onNodeWithText("Unlock").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Message").assertIsDisplayed() // followed: the chat's composer
        assertNull(graph.requestedChat.value)
        // Back to the list, lock and unlock again: the old request is not followed a second time.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        auth.answerWith(AuthResult.Cancelled) // the automatic prompt on return is dismissed
        rule.runOnUiThread { graph.appLock.onStop(); graph.appLock.onStart() }
        rule.waitForIdle()
        rule.onNodeWithText("Unlock").assertIsDisplayed()
        rule.onNodeWithText("Unlock").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Riya").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount("Message")) // no chat open
    }

    @Test fun aChatThatIsNotTheAccountsIsDroppedAfterTheUnlock() {
        show(lockOn = true)
        graph.requestedChat.value = "not-a-chat"
        rule.onNodeWithText("Unlock").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Riya").assertIsDisplayed()
        assertNull(graph.requestedChat.value)
    }

    @Test fun anActivityRecreationDoesNotUnlock() {
        show(lockOn = true)
        // The lock lives in the graph, not in the screen: a recreated screen over the same graph is still locked.
        assertTrue(graph.appLock.locked.value)
        rule.activityRule.scenario.recreate()
        assertTrue(graph.appLock.locked.value)
    }

    @Test fun aRotationDoesNotStartTheTimerOrPromptAgain() {
        show(lockOn = true)
        val before = auth.requests
        rule.runOnUiThread { graph.appLock.onStart() } // the recreated activity starts; no onStop came before
        rule.waitForIdle()
        assertEquals(before, auth.requests)
    }

    @Test fun aCallWhileLockedShowsTheCallersNameOnlyAndLeavingItReturnsToTheCover() {
        show(lockOn = true)
        val engine = graph.callEngine as SimulatedCallEngine
        rule.runOnUiThread { engine.start("riya", false) }
        rule.waitForIdle()
        assertTrue("Riya" in rule.allLabels())
        assertFalse(rule.allLabels().any { it.contains("See you at 6") })
        assertEquals(0, rule.onAllNodesWithTextCount("Chats"))
        rule.runOnUiThread { engine.hangUp() }
        rule.mainClock.advanceTimeBy(5_000)
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTextCount("Riya"))
        rule.onNodeWithText("Unlock").assertIsDisplayed()
    }

    @Test fun signOutResetsTheSettingAndTheCoverGoes() {
        show(lockOn = true)
        rule.onNodeWithText("Unlock").assertIsDisplayed()
        graph.settingsStore.reset(org.example.app.core.settings.UserSettings())
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTextCount("Unlock"))
        assertFalse(graph.appLockOn)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
