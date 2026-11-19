package org.example.app.ui.onboarding

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.example.app.core.account.AccountError
import org.example.app.core.account.AccountService
import org.example.app.core.account.OnboardingStep
import org.example.app.core.account.SampleAccountService
import org.example.app.core.account.UsernameCheck
import org.example.app.ui.theme.AppTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class OnboardingRedesignTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class Expiring(val inner: SampleAccountService = SampleAccountService()) : AccountService by inner {
        override suspend fun finishSignUp() { throw AccountError.SignupExpired }
    }

    private class NoPasskeys(val inner: SampleAccountService = SampleAccountService()) : AccountService by inner {
        override val supportsPasskeys: Boolean get() = false
    }

    /** Linking waits for the other phone instead of approving at once, so the link screen stays up. */
    private class Waiting(val inner: SampleAccountService = SampleAccountService()) : AccountService by inner {
        override suspend fun waitForLinkApproval(): org.example.app.core.account.LinkedResult = kotlinx.coroutines.awaitCancellation()
    }

    private fun host(service: AccountService): OnboardingViewModel {
        val vm = OnboardingViewModel(service, saved = SavedStateHandle())
        rule.setContent { AppTheme { OnboardingHost(vm, onFinished = {}) } }
        return vm
    }

    private fun toAccountStep(vm: OnboardingViewModel) {
        vm.getStarted(); vm.chooseDefaultServer(); vm.submitInvite(SampleAccountService.SAMPLE_INVITE_CODE)
        rule.waitForIdle()
    }

    @Test
    fun welcomeHasNoBackBar() {
        host(SampleAccountService())
        rule.onAllNodesWithContentDescription("Back").assertCountEquals(0)
    }

    @Test
    fun everyOtherStepHasTheBackBar() {
        val vm = host(SampleAccountService())
        vm.getStarted(); rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").assertIsDisplayed() // join server
        vm.chooseDefaultServer(); rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").assertIsDisplayed() // invite code
        vm.submitInvite(SampleAccountService.SAMPLE_INVITE_CODE); rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").assertIsDisplayed() // create account
        vm.submitAccount("Asha", "asha", "a@example.com"); rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").assertIsDisplayed() // verify email
        vm.verifyEmail(SampleAccountService.SAMPLE_EMAIL_CODE); rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").assertIsDisplayed() // passkey
        vm.useEmailCodeInstead(); rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").assertIsDisplayed() // permissions
    }

    @Test
    fun linkAndLostDeviceStepsHaveTheBackBar() {
        val vm = host(Waiting())
        vm.alreadyHaveAccount(); rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").assertIsDisplayed()
        vm.noOldDevice(); rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun backBarGoesBack() {
        val vm = host(SampleAccountService())
        vm.getStarted(); rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        assertEquals(OnboardingStep.Welcome, vm.state.value.step)
    }

    @Test
    fun signupExpiredShowsStartAgainAndKeepsTheFields() {
        val vm = host(Expiring())
        toAccountStep(vm)
        vm.checkUsername("asha")
        rule.onNodeWithText("Name").performTextInput("Asha Rao")
        rule.onNodeWithText("Username").performTextInput("asha")
        rule.onNodeWithText("Email").performTextInput("asha@example.com")
        rule.waitForIdle()
        rule.onNodeWithText("Continue").performClick()
        rule.waitForIdle()
        vm.verifyEmail(SampleAccountService.SAMPLE_EMAIL_CODE); rule.waitForIdle()
        vm.createPasskey(); rule.waitForIdle()
        rule.onNodeWithText("This sign-up has expired. Start again.").assertIsDisplayed()
        rule.onNodeWithText("Start again").performClick()
        rule.waitForIdle()
        assertEquals(OnboardingStep.CreateAccount, vm.state.value.step)
        rule.onNodeWithText("Asha Rao").assertIsDisplayed()
        rule.onNodeWithText("asha").assertIsDisplayed()
        rule.onNodeWithText("asha@example.com").assertIsDisplayed()
    }

    @Test
    fun passkeyStepIsSkippedWhenUnsupported() {
        val vm = host(NoPasskeys())
        toAccountStep(vm)
        vm.submitAccount("Asha", "asha", "a@example.com"); rule.waitForIdle()
        vm.verifyEmail(SampleAccountService.SAMPLE_EMAIL_CODE); rule.waitForIdle()
        assertEquals(OnboardingStep.Permissions, vm.state.value.step)
    }

    @Test
    fun lostDeviceWithoutPasskeysOffersOnlyTheEmailCode() {
        rule.setContent {
            AppTheme { LostDeviceScreen(false, null, false, onPasskey = {}, onSendCode = {}, onSignIn = { _, _ -> }, supportsPasskeys = false) }
        }
        rule.onNodeWithText("Sign in with passkey").assertDoesNotExist()
        rule.onNodeWithText("Send code").assertIsDisplayed()
    }

    @Test
    fun usernameErrorIsShownUnderItsField() {
        rule.setContent { AppTheme { CreateAccountScreen("h", UsernameCheck.Taken, null, false, {}, { _, _, _ -> }) } }
        rule.onNodeWithText("Taken").assertIsDisplayed()
    }

    @Test
    fun invalidEmailErrorIsShownUnderTheEmailField() {
        rule.setContent { AppTheme { CreateAccountScreen("h", null, AccountError.InvalidEmail, false, {}, { _, _, _ -> }) } }
        rule.onNodeWithText("Enter a valid email address.").assertIsDisplayed()
        // The help text is replaced by the error under the same field.
        rule.onNodeWithText("Only used to recover your account. Nobody can find you by email unless you turn it on.").assertDoesNotExist()
    }

    @Test
    fun wrongCodeErrorIsShownUnderTheCodeField() {
        rule.setContent { AppTheme { VerifyEmailScreen(AccountError.CodeInvalid, false) {} } }
        rule.onNodeWithText("That code isn't right.").assertIsDisplayed()
    }
}
