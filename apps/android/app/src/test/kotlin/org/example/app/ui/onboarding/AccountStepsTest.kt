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

import org.example.app.core.account.UsernameCheck
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class AccountStepsTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun createAccountShowsEmailHelpFromTheDesign() {
        rule.setContent { AppTheme { CreateAccountScreen("home.example.org", null, null, false, {}, { _, _, _ -> }) } }
        rule.onNodeWithText("Only used to recover your account. Nobody can find you by email unless you turn it on.").assertExists()
        rule.onNodeWithText("Joining home.example.org").assertExists()
    }

    @Test
    fun createAccountContinuesOnlyWhenEverythingIsFilledAndUsernameAvailable() {
        val submitted = mutableListOf<Triple<String, String, String>>()
        var check by androidx.compose.runtime.mutableStateOf<UsernameCheck?>(null)
        rule.setContent { AppTheme { CreateAccountScreen("home.example.org", check, null, false, { }, { a, b, c -> submitted += Triple(a, b, c) }) } }
        rule.onNodeWithText("Name").performTextInput("Asha")
        rule.onNodeWithText("Username").performTextInput("asha")
        rule.onNodeWithText("Email").performTextInput("asha@example.com")
        rule.onNodeWithText("Continue").assertIsNotEnabled()
        check = UsernameCheck.Available
        rule.onNodeWithText("✓ Available").assertExists()
        rule.onNodeWithText("Continue").assertIsEnabled().performClick()
        assertEquals(listOf(Triple("Asha", "asha", "asha@example.com")), submitted)
    }

    @Test
    fun usernameStatusTextsAreShown() {
        rule.setContent { AppTheme { CreateAccountScreen("h", UsernameCheck.Taken, null, false, {}, { _, _, _ -> }) } }
        rule.onNodeWithText("Taken").assertExists()
    }

    @Test
    fun codeFieldAcceptsSixDigitsOnly() {
        val verified = mutableListOf<String>()
        rule.setContent { AppTheme { VerifyEmailScreen(null, false) { verified += it } } }
        rule.onNodeWithText("Verify").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("12ab34-5678")
        rule.onNodeWithText("123456").assertExists()
        rule.onNodeWithText("Verify").assertIsEnabled().performClick()
        assertEquals(listOf("123456"), verified)
    }

    @Test
    fun shortCodeCannotBeSubmitted() {
        rule.setContent { AppTheme { VerifyEmailScreen(null, false) {} } }
        rule.onNode(hasSetTextAction()).performTextInput("123")
        rule.onNodeWithText("Verify").assertIsNotEnabled()
    }

    @Test
    fun inviteCodeScreenSubmitsTheCode() {
        val got = mutableListOf<String>()
        rule.setContent { AppTheme { InviteCodeScreen(null, false) { got += it } } }
        rule.onNode(hasSetTextAction()).performTextInput("abcde-fgh23")
        rule.onNodeWithText("Continue").performClick()
        assertEquals(listOf("ABCDE-FGH23"), got)
    }

    @Test
    fun errorsAreShownInPlainWords() {
        rule.setContent { AppTheme { InviteCodeScreen(org.example.app.core.account.AccountError.InviteInvalid, false) {} } }
        rule.onNodeWithText("That invite code doesn't work. Check it and try again.").assertExists()
    }
}
