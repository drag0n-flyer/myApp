package org.example.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.settings.UserSettings
import org.example.app.ui.lock.AppLock
import org.example.app.ui.lock.AuthResult
import org.example.app.ui.lock.SampleDeviceAuth
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class PrivacyAppLockTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val settings = SettingsStore()
    private val lock = AppLock({ settings.settings.value })

    private fun show(auth: SampleDeviceAuth) = rule.setContent {
        AppTheme { PrivacyScreen(settings, SampleChatStore(), lock, auth, saveFindByEmail = {}, loadFindByEmail = { null }, onBack = {}) }
    }

    @Test fun theSwitchAndItsSubtitleUseTheDesignsWords() {
        show(SampleDeviceAuth())
        rule.onNodeWithText("App lock").assertIsDisplayed()
        rule.onNodeWithText("Ask for your fingerprint, face or screen lock to open [App]").assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(hasText("Lock after")).fetchSemanticsNodes().size)
    }

    @Test fun turningItOnNeedsOnePassedPrompt() {
        val auth = SampleDeviceAuth(results = listOf(AuthResult.Cancelled, AuthResult.Success))
        show(auth)
        rule.onNodeWithText("App lock").performClick()
        assertFalse(settings.settings.value.appLock) // the prompt was cancelled
        rule.onNodeWithText("App lock").performClick()
        rule.waitForIdle()
        assertEquals(2, auth.requests)
        assertTrue(settings.settings.value.appLock)
        assertFalse(lock.locked.value) // it was just passed
        rule.onNodeWithText("Lock after").assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasText("1 minute")).fetchSemanticsNodes().isNotEmpty()) // the default
    }

    @Test fun withoutAScreenLockTheSwitchIsDisabledWithTheReason() {
        val auth = SampleDeviceAuth(available = false)
        show(auth)
        rule.onNodeWithText("Set a screen lock in your phone's settings first.").assertIsDisplayed()
        rule.onNodeWithText("App lock").assertIsNotEnabled()
        rule.onNodeWithText("App lock").performClick()
        assertEquals(0, auth.requests)
        assertFalse(settings.settings.value.appLock)
    }

    @Test fun turningItOffNeedsThePromptToo() {
        settings.update { it.copy(appLock = true) }
        val auth = SampleDeviceAuth(results = listOf(AuthResult.Cancelled, AuthResult.Success))
        show(auth)
        rule.onNodeWithText("App lock").performClick()
        assertTrue(settings.settings.value.appLock) // cancelled: still on
        rule.onNodeWithText("App lock").performClick()
        rule.waitForIdle()
        assertFalse(settings.settings.value.appLock)
        assertEquals(2, auth.requests)
    }

    @Test fun aPromptThatPassesAfterASignOutChangesNothing() {
        val pending = mutableListOf<(AuthResult) -> Unit>()
        val auth = object : org.example.app.ui.lock.DeviceAuth {
            override val available = true
            override fun authenticate(title: String, onResult: (AuthResult) -> Unit) { pending += onResult }
            override fun cancel() {}
        }
        rule.setContent {
            AppTheme { PrivacyScreen(settings, SampleChatStore(), lock, auth, saveFindByEmail = {}, loadFindByEmail = { null }, onBack = {}) }
        }
        rule.onNodeWithText("App lock").performClick()
        settings.reset(UserSettings()) // signed out while the sheet was open
        pending.single()(AuthResult.Success)
        rule.waitForIdle()
        assertFalse(settings.settings.value.appLock)
    }

    @Test fun lockAfterOffersTheFourChoicesAndSavesTheOneTapped() {
        settings.update { it.copy(appLock = true) }
        show(SampleDeviceAuth())
        rule.onNodeWithText("Lock after").performClick()
        for (label in listOf("Immediately", "5 minutes", "1 hour")) rule.onNodeWithText(label).assertIsDisplayed()
        assertEquals(2, rule.onAllNodes(hasText("1 minute")).fetchSemanticsNodes().size) // the row's value and the dialog's choice
        rule.onNodeWithText("5 minutes").performClick()
        assertEquals(300, settings.settings.value.lockAfterSeconds)
    }

    @Test fun signOutResetsTheLockWithTheOtherSettings() {
        settings.update { it.copy(appLock = true, lockAfterSeconds = 3600) }
        settings.reset(UserSettings())
        assertFalse(settings.settings.value.appLock)
        assertEquals(60, settings.settings.value.lockAfterSeconds)
    }
}
