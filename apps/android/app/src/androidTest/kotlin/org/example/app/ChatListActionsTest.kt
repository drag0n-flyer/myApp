package org.example.app

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/** Chat list actions on sample data, on a real (emulated) device: pin to the top, archive and find it in the archive. */
@RunWith(AndroidJUnit4::class)
class ChatListActionsTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SAMPLE_DATA, true)
        scenario = ActivityScenario.launch(intent)
        rule.onNodeWithText("Riya").assertIsDisplayed()
    }

    @After
    fun close() = scenario.close()

    private fun longPress(name: String) {
        rule.onNodeWithText(name).performTouchInput { longClick() }
    }

    @Test
    fun pinningAChatPutsItFirst() {
        // Riya has the newest message, so she is first until Sam is pinned.
        assertTrue(top("Riya") < top("Sam"))
        longPress("Sam")
        rule.onNodeWithText("Pin").performClick()
        rule.onNodeWithContentDescription("Pinned").assertIsDisplayed()
        rule.waitUntil(5_000) { top("Sam") < top("Riya") }
        longPress("Sam")
        rule.onNodeWithText("Unpin").assertIsDisplayed()
    }

    @Test
    fun archivingAChatMovesItToTheArchive() {
        longPress("Sam")
        rule.onNodeWithText("Archive").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Sam") == 0 }
        rule.onNodeWithText("Archived").assertIsDisplayed()
        rule.onNodeWithText("Archived").performClick()
        rule.onNodeWithText("Sam").assertIsDisplayed()
        longPress("Sam")
        rule.onNodeWithText("Unarchive").assertIsDisplayed()
    }

    private fun top(name: String) = rule.onNodeWithText(name).getUnclippedBoundsInRoot().top.value

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
