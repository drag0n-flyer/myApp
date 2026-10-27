package org.example.app.ui.onboarding

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlin.test.assertEquals
import org.example.app.ui.qr.CameraPermissionGate
import kotlin.test.assertTrue
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class PasskeyAndPermissionsTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun passkeyScreenUsesScreenLockWording() {
        var created = 0; var email = 0
        rule.setContent { AppTheme { PasskeyScreen(true, null, false, { created++ }, { email++ }) } }
        rule.onNodeWithText("Use your screen lock to sign in").assertExists()
        rule.onNodeWithText("Create passkey").performClick()
        rule.onNodeWithText("Use an email code instead").performClick()
        assertEquals(1, created); assertEquals(1, email)
    }

    @Test
    fun unsupportedServerExplainsEmailCode() {
        rule.setContent { AppTheme { PasskeyScreen(false, null, false, {}, {}) } }
        rule.onNodeWithText("Create passkey").assertDoesNotExist()
        rule.onNodeWithText("Passkeys aren't available on this server. You'll sign in with an email code instead.").assertExists()
    }

    @Test
    fun permissionsShowTheReasonBeforeAnySystemPrompt() {
        rule.setContent { AppTheme { PermissionsScreen(onRequest = {}, onDone = {}) } }
        rule.onNodeWithText("So you know when someone messages or calls you.").assertExists()
        rule.onNodeWithText("So people can hear you on calls.").assertExists()
    }

    @Test
    fun continueAsksForThePermissions() {
        var asked = 0
        rule.setContent { AppTheme { PermissionsScreen(onRequest = { asked++ }, onDone = {}) } }
        rule.onNodeWithText("Continue").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun cameraReasonComesBeforeTheCameraPrompt() {
        var asked = 0
        rule.setContent { AppTheme { CameraPermissionGate(granted = false, onRequest = { asked++ }) { androidx.compose.material3.Text("preview") } } }
        rule.onNodeWithText("So you can scan a QR code. Nothing is recorded.").assertExists()
        rule.onNodeWithText("preview").assertDoesNotExist()
        rule.onNodeWithText("Allow camera").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun grantedCameraShowsThePreview() {
        rule.setContent { AppTheme { CameraPermissionGate(granted = true, onRequest = {}) { androidx.compose.material3.Text("preview") } } }
        rule.onNodeWithText("preview").assertExists()
    }
}
