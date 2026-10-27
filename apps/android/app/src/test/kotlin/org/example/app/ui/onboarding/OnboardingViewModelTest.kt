package org.example.app.ui.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.example.app.core.account.AccountError
import org.example.app.core.account.OnboardingStep
import org.example.app.core.account.SampleAccountService
import org.example.app.core.account.UsernameCheck
import org.junit.After
import org.junit.Before

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun signUpWalksEveryStepAndSignsIn() {
        val service = SampleAccountService()
        val vm = OnboardingViewModel(service)
        vm.getStarted()
        vm.chooseDefaultServer()
        assertEquals(OnboardingStep.InviteCode, vm.state.value.step)
        vm.submitInvite(SampleAccountService.SAMPLE_INVITE_CODE)
        assertEquals(OnboardingStep.CreateAccount, vm.state.value.step)
        vm.checkUsername("asha")
        assertEquals(UsernameCheck.Available, vm.state.value.usernameCheck)
        vm.submitAccount("Asha", "asha", "asha@example.com")
        assertEquals(OnboardingStep.VerifyEmail, vm.state.value.step)
        vm.verifyEmail(SampleAccountService.SAMPLE_EMAIL_CODE)
        assertEquals(OnboardingStep.CreatePasskey, vm.state.value.step)
        vm.createPasskey()
        assertEquals(OnboardingStep.Permissions, vm.state.value.step)
        vm.permissionsHandled()
        assertEquals(OnboardingStep.Done, vm.state.value.step)
        assertTrue(service.isSignedIn)
    }

    @Test
    fun wrongInviteCodeStaysOnTheStepWithAnError() {
        val vm = OnboardingViewModel(SampleAccountService())
        vm.getStarted()
        vm.chooseDefaultServer()
        vm.submitInvite("ZZZZZ-ZZZZ2")
        assertEquals(OnboardingStep.InviteCode, vm.state.value.step)
        assertEquals(AccountError.InviteInvalid, vm.state.value.error)
    }

    @Test
    fun wrongEmailCodeKeepsTheStep() {
        val vm = OnboardingViewModel(SampleAccountService())
        vm.getStarted(); vm.chooseDefaultServer(); vm.submitInvite(SampleAccountService.SAMPLE_INVITE_CODE)
        vm.submitAccount("A", "asha", "a@example.com")
        vm.verifyEmail("000000")
        assertEquals(OnboardingStep.VerifyEmail, vm.state.value.step)
        assertEquals(AccountError.CodeInvalid, vm.state.value.error)
    }

    @Test
    fun invitePastedAsAppLinkSkipsTheInviteStep() {
        val vm = OnboardingViewModel(SampleAccountService())
        vm.getStarted()
        assertTrue(vm.chooseInviteText("https://home.example.org/invite/ABCDE-FGH23"))
        assertEquals(OnboardingStep.CreateAccount, vm.state.value.step)
        assertTrue(!vm.chooseInviteText("junk"))
    }

    @Test
    fun linkingFinishesWhenTheOtherDeviceApproves() {
        val service = SampleAccountService()
        val vm = OnboardingViewModel(service)
        vm.alreadyHaveAccount()
        vm.startLinking()
        assertEquals(OnboardingStep.Done, vm.state.value.step)
        assertTrue(service.isSignedIn)
        assertEquals(SampleAccountService.SAMPLE_LINK_TEXT, vm.state.value.linkCode?.qrText)
    }

    @Test
    fun approvalNeedsBiometricsFirst() {
        val service = SampleAccountService()
        var allow = false
        val vm = LinkApprovalViewModel(service) { allow }
        vm.scanned("https://home.example.org/link/x#y")
        assertTrue(vm.state.value.request != null)
        vm.approve()
        assertEquals(AccountError.BiometricsUnavailable, vm.state.value.error)
        allow = true
        vm.approve()
        assertTrue(vm.state.value.approved)
    }

    @Test
    fun scanningACodeForAnotherServerIsRefused() {
        val vm = LinkApprovalViewModel(SampleAccountService()) { true }
        vm.scanned("https://other.example.net/link/x#y")
        assertEquals(AccountError.WrongServer, vm.state.value.error)
    }
}
