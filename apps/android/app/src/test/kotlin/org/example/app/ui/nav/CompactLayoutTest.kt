package org.example.app.ui.nav

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import org.example.app.MainActivity
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A phone-width window keeps today's layout: the bottom bar and one pane, no empty detail pane. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h900dp")
class CompactLayoutTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun close() { scenario?.close() }

    @Test fun compactWidthHasNoDetailPaneAndOpensAChatFullScreen() {
        scenario = ActivityScenario.launch(
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SAMPLE_DATA, true),
        )
        rule.waitForIdle()
        rule.onNodeWithText("Riya").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Select a chat").fetchSemanticsNodes().size)
        rule.onNode(isSelected() and hasText("Chats")).assertExists()
        rule.onNodeWithText("Riya").performClick()
        rule.waitForIdle()
        // The chat is a screen of its own: the list row is gone.
        assertEquals(0, rule.onAllNodesWithText("Arjun").fetchSemanticsNodes().size)
    }
}
