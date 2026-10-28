package org.example.app.ui.settings

import org.example.app.core.settings.NoiseSuppression
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.ui.allLabels
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class CallsAudioScreenTest {
    @get:Rule val rule = createComposeRule()
    private val settings = SettingsStore()

    @Test
    fun offersThreeChoicesAndNoVoiceIsolation() {
        rule.setContent { AppTheme { CallsAudioScreen(settings, onBack = {}) } }
        rule.onNodeWithText("Standard").assertIsSelected()
        rule.onNodeWithText("Enhanced").assertIsDisplayed()
        rule.onNodeWithText("Off").assertIsDisplayed()
        assertTrue(rule.allLabels().none { it.contains("Voice Isolation", ignoreCase = true) })
        rule.onNodeWithText("Enhanced").performClick()
        rule.waitForIdle()
        assertEquals(NoiseSuppression.Enhanced, settings.settings.value.noiseSuppression)
    }
}
