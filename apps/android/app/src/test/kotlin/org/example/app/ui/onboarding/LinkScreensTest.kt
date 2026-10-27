package org.example.app.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import java.time.Instant
import org.example.app.core.account.AccountError
import org.example.app.core.account.DevicePlatform
import org.example.app.core.account.LinkCode
import org.example.app.core.account.LinkRequest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class LinkScreensTest {
    @get:Rule val rule = createComposeRule()

    private val request = LinkRequest("https://home.example.org/link/x#y", "New Pixel", DevicePlatform.Android, Instant.now())

    @Test
    fun approvalShowsDeviceTypeTimeAndWarning() {
        var approved = 0; var cancelled = 0
        rule.setContent { AppTheme { LinkApprovalScreen(request, null, false, { approved++ }, { cancelled++ }) } }
        rule.onNodeWithText("New Pixel").assertExists()
        rule.onNodeWithText("Android").assertExists()
        rule.onNodeWithText("Asked").assertExists()
        rule.onNodeWithText("This device will be able to read all your future messages. Only approve it if it's yours and you started linking it just now.").assertExists()
        rule.onNodeWithText("Approve").performClick()
        rule.onNodeWithText("Don't approve").performClick()
        assertEquals(1, approved); assertEquals(1, cancelled)
    }

    @Test
    fun approvalShowsBiometricsErrorInPlainWords() {
        rule.setContent { AppTheme { LinkApprovalScreen(request, AccountError.BiometricsUnavailable, false, {}, {}) } }
        rule.onNodeWithText("Couldn't confirm it's you. Check your fingerprint or face and try again.").assertExists()
    }

    @Test
    fun linkDeviceShowsQrServerAndNewCodeWhenExpired() {
        var fresh = 0
        rule.setContent {
            AppTheme { LinkDeviceScreen("home.example.org", LinkCode("https://home.example.org/link/a#b", Instant.now()), true, null, canCreateAccount = true, onNewCode = { fresh++ }, onChangeServer = {}, onNoOldDevice = {}) }
        }
        rule.onNodeWithContentDescription("Linking QR code").assertExists()
        rule.onNodeWithText("Server: home.example.org").assertExists()
        rule.onNodeWithText("Show new code").performClick()
        assertEquals(1, fresh)
        rule.onNodeWithText("I don't have my old device").assertExists()
    }

    @Test
    fun lostDeviceOffersPasskeyThenEmailCode() {
        val sent = mutableListOf<String>(); val signed = mutableListOf<Pair<String, String>>()
        var codeSent by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent { AppTheme { LostDeviceScreen(codeSent, null, false, {}, { sent += it }, { e, c -> signed += e to c }) } }
        rule.onNodeWithText("Sign in with passkey").assertExists()
        rule.onNodeWithText("Email").performTextInput("a@example.com")
        rule.onNodeWithText("Send code").performClick()
        assertEquals(listOf("a@example.com"), sent)
        codeSent = true
        rule.onNodeWithText("Code").performTextInput("123456")
        rule.onNodeWithText("Sign in").performClick()
        assertEquals(listOf("a@example.com" to "123456"), signed)
    }
}
