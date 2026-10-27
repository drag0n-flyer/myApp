package org.example.app.ui.settings

import org.example.app.core.chats.SampleChatStore
import org.example.app.core.settings.IPHiding
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
class PrivacyScreenTest {
    @get:Rule val rule = createComposeRule()
    private val settings = SettingsStore()

    private fun launch() = rule.setContent { AppTheme { PrivacyScreen(settings, SampleChatStore(), onBack = {}) } }

    @Test
    fun hideIpOffersThreeChoicesWithRecommendedSelected() {
        launch()
        rule.onNodeWithText("Hide my IP address in calls").assertIsDisplayed()
        rule.onNodeWithText("Never").assertIsDisplayed()
        rule.onNodeWithText("With people not in my contacts").assertIsDisplayed()
        rule.onNodeWithText("Always").assertIsDisplayed()
        rule.onNodeWithText("Recommended").assertIsDisplayed()
        rule.onNodeWithText("With people not in my contacts").assertIsSelected()
    }

    @Test
    fun choosingAnOptionUpdatesTheSetting() {
        launch()
        rule.onNodeWithText("Always").performClick()
        rule.waitForIdle()
        assertEquals(IPHiding.Always, settings.settings.value.ipHiding)
        rule.onNodeWithText("Always").assertIsSelected()
    }

    @Test
    fun findByEmailIsOffByDefault() {
        launch()
        rule.onNodeWithText("By email").assertIsDisplayed()
        rule.onNodeWithText("By username").assertIsOn()
    }

    @Test
    fun usesPlainWords() {
        launch()
        val text = rule.allLabels().joinToString(" ")
        assertTrue(Regex("relay|MLS|E2EE|codec|kbps", RegexOption.IGNORE_CASE).containsMatchIn(text).not())
    }
}
