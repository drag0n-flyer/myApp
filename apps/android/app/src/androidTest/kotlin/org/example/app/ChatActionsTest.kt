package org.example.app

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Message actions and history on sample data, on a real (emulated) device: menu, reply bar, edit bar, scroll button. */
@RunWith(AndroidJUnit4::class)
class ChatActionsTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SAMPLE_DATA, true)
        scenario = ActivityScenario.launch(intent)
        rule.onNodeWithText("Riya").performClick()
    }

    @After
    fun close() = scenario.close()

    @Test
    fun longPressShowsReplyAndCopy() {
        rule.onNodeWithText("See you at 6, call me when you leave").performTouchInput { longClick() }
        rule.onNodeWithText("Reply").assertIsDisplayed()
        rule.onNodeWithText("Copy").assertIsDisplayed()
        rule.onNodeWithContentDescription("React with 👍").assertIsDisplayed()
    }

    @Test
    fun replyShowsTheBarAndTheSentMessageCarriesAQuote() {
        rule.onNodeWithText("See you at 6, call me when you leave").performTouchInput { longClick() }
        rule.onNodeWithText("Reply").performClick()
        rule.onNodeWithText("Replying to Riya").assertIsDisplayed()
        rule.onNodeWithText("Message").performTextInput("on my way")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("on my way")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Reply to Riya: See you at 6, call me when you leave").assertIsDisplayed()
    }

    @Test
    fun editShowsTheEditingBar() {
        rule.onNodeWithText("Call me when you leave").performTouchInput { longClick() }
        rule.onNodeWithText("Edit").performClick()
        rule.onNodeWithText("Editing").assertIsDisplayed()
    }

    @Test
    fun scrollingUpShowsTheScrollToNewestButton() {
        repeat(25) { n ->
            rule.onNodeWithText("Message").performTextInput("filler $n")
            rule.onNodeWithContentDescription("Send").performClick()
            rule.waitUntil(5_000) { rule.onAllNodes(hasText("filler $n")).fetchSemanticsNodes().isNotEmpty() }
        }
        rule.onNodeWithContentDescription("Scroll to newest").assertDoesNotExist()
        rule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Scroll to newest")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Scroll to newest").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Scroll to newest")).fetchSemanticsNodes().isEmpty() }
    }

}
