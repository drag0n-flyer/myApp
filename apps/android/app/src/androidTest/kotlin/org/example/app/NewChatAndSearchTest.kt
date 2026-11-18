package org.example.app

import android.content.Intent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Sample mode: the new-chat button opens a contact's chat, and search finds a contact by name. */
@RunWith(AndroidJUnit4::class)
class NewChatAndSearchTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SAMPLE_DATA, true)
        scenario = ActivityScenario.launch(intent)
    }

    @After
    fun close() = scenario.close()

    @Test
    fun newChatPicksAContactAndOpensTheChat() {
        rule.onNodeWithContentDescription("New chat").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Add contact")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Meera").performClick()
        // The chat screen has the message field.
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Message")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Message").performTextInput("hi")
        rule.onNodeWithContentDescription("Send").performClick()
    }

    @Test
    fun searchFindsAContactByName() {
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("kiran")
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Kiran")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Kiran").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Audio call")).fetchSemanticsNodes().isNotEmpty() }
    }
}
