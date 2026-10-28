package org.example.app

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the app on sample data, opens a chat, sends a message and starts a simulated call.
 * Runs once the emulator CI job (task G3) lands; until then it only compiles.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchOpenChatSendAndCall() {
        rule.onNodeWithText("Riya").performClick()
        rule.onNodeWithText("Message").performTextInput("hello from the smoke test")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasText("hello from the smoke test")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Audio call").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasContentDescription("End")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("End").performClick()
    }
}
