package org.example.app.ui.settings

import androidx.compose.ui.test.assertIsOff
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.settings.DownloadScope
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
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
class DataStorageScreenTest {
    @get:Rule val rule = createComposeRule()
    private val settings = SettingsStore()
    private val server = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")

    private fun launch() = rule.setContent { AppTheme { DataStorageScreen(settings, server, onBack = {}) } }

    @Test
    fun showsTheDefaults() {
        launch()
        rule.onNodeWithText("Call data mode").assertIsDisplayed()
        rule.onNodeWithText("Auto").assertIsDisplayed()
        rule.onNodeWithText("Limit on mobile data").assertIsDisplayed()
        rule.onNodeWithText("Data saver").assertIsDisplayed()
        rule.onNodeWithText("Start video calls with video off on mobile").assertIsOn()
        rule.onNodeWithText("On Wi-Fi").assertIsDisplayed()
        rule.onNodeWithText("All").assertIsDisplayed()
        rule.onNodeWithText("On mobile data").assertIsDisplayed()
        rule.onNodeWithText("None").assertIsDisplayed()
        rule.onNodeWithText("Voice messages always").assertIsOn()
        rule.onNodeWithText("Server default: Auto · Server maximum: High quality").assertIsDisplayed()
    }

    @Test
    fun theDefaultsMatchTheSettingsModel() {
        val s = settings.settings.value
        assertEquals(ModePreference.Auto, s.call.callDataMode)
        assertEquals(DataMode.DataSaver, s.call.mobileDataLimit)
        assertEquals(DownloadScope.All, s.autoDownload.onWiFi)
        assertEquals(DownloadScope.None, s.autoDownload.onMobile)
        assertTrue(s.autoDownload.voiceAlways && s.call.videoOffOnMobile)
    }

    @Test
    fun pickingAModeUpdatesTheSetting() {
        launch()
        rule.onNodeWithText("Call data mode").performClick()
        rule.onNodeWithText("Ultra-low").performClick()
        rule.waitForIdle()
        assertEquals(ModePreference.Fixed(DataMode.UltraLow), settings.settings.value.call.callDataMode)
    }

    @Test
    fun togglingVideoOffAndVoice() {
        launch()
        rule.onNodeWithText("Start video calls with video off on mobile").performClick()
        rule.onNodeWithText("Voice messages always").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Start video calls with video off on mobile").assertIsOff()
        assertTrue(!settings.settings.value.call.videoOffOnMobile && !settings.settings.value.autoDownload.voiceAlways)
    }
}
