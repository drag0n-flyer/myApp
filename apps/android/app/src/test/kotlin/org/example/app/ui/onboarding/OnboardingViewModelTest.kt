package org.example.app.ui.onboarding

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import org.example.app.core.account.AccountError
import org.example.app.core.account.AccountService
import org.example.app.core.account.LinkCode
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

    /** A sample service that records what the view model asked of it. */
    private class Recording(val inner: SampleAccountService = SampleAccountService()) : AccountService by inner {
        val connected = mutableListOf<String>()
        var codes = 0
        val approval = CompletableDeferred<Unit>()
        override suspend fun connect(host: String) = inner.connect(host).also { connected += host }
        override suspend fun makeLinkCode(): LinkCode { codes++; return LinkCode("https://home.example.org/link/c$codes#k", Instant.now().plusSeconds(300)) }
        override suspend fun waitForLinkApproval() { approval.await(); inner.waitForLinkApproval() }
    }

    @Test
    fun signUpSendsTheTypedName() {
        val service = SampleAccountService()
        val vm = OnboardingViewModel(service)
        vm.getStarted(); vm.chooseDefaultServer(); vm.submitInvite(SampleAccountService.SAMPLE_INVITE_CODE)
        vm.submitAccount("  Asha Rao ", "asha", "asha@example.com")
        assertEquals("Asha Rao", service.displayName)
    }

    @Test
    fun emailCodeInsteadFinishesWithoutAPasskey() {
        val service = SampleAccountService()
        val vm = OnboardingViewModel(service)
        vm.getStarted(); vm.chooseDefaultServer(); vm.submitInvite(SampleAccountService.SAMPLE_INVITE_CODE)
        vm.submitAccount("Asha", "asha", "asha@example.com")
        vm.verifyEmail(SampleAccountService.SAMPLE_EMAIL_CODE)
        vm.useEmailCodeInstead()
        assertTrue(service.isSignedIn)
        assertEquals(false, service.passkeyRegistered)
        assertEquals(OnboardingStep.Permissions, vm.state.value.step)
    }

    @Test
    fun badServerAddressesNeverReachTheService() {
        val service = Recording()
        val vm = OnboardingViewModel(service)
        vm.getStarted()
        for (bad in listOf("http://home.example.org", "user@home.example.org", "home.example.org/x", "a b", "x".repeat(300))) {
            vm.chooseAddress(bad)
            assertEquals(AccountError.InvalidServerAddress, vm.state.value.error, bad)
            assertEquals(OnboardingStep.JoinServer, vm.state.value.step)
        }
        assertTrue(service.connected.isEmpty())
        vm.chooseAddress(" HTTPS://Home.Example.org:8443 ")
        assertEquals(listOf("home.example.org:8443"), service.connected)
    }

    @Test
    fun inviteLinkKeepsItsPortAndHostIsChecked() {
        val service = Recording()
        val vm = OnboardingViewModel(service)
        vm.getStarted()
        assertTrue(vm.chooseInviteText("https://home.example.org:8443/invite/ABCDE-FGH23"))
        assertEquals(listOf("home.example.org:8443"), service.connected)
        assertTrue(!vm.chooseInviteText("https://home.example.org/invite/ABCDE-FGH23" + "x".repeat(2100)))
    }

    @Test
    fun changeLinkServerIsValidatedToo() {
        val service = Recording()
        val vm = OnboardingViewModel(service)
        vm.alreadyHaveAccount()
        vm.changeLinkServer("http://evil.example.net")
        assertEquals(AccountError.InvalidServerAddress, vm.state.value.error)
        assertTrue(service.connected.isEmpty())
    }

    @Test
    fun linkCodeSurvivesRecreationAndIsNotRemade() {
        val saved = SavedStateHandle()
        val service = Recording()
        val vm = OnboardingViewModel(service, saved = saved)
        vm.alreadyHaveAccount()
        vm.ensureLinking()
        vm.ensureLinking()
        assertEquals(1, service.codes)
        val shown = vm.state.value.linkCode
        assertNotNull(shown)
        // A recreated view model restores the same code and waits on it instead of making another.
        val again = OnboardingViewModel(service, saved = saved)
        assertEquals(shown.qrText, again.state.value.linkCode?.qrText)
        assertEquals(shown.expiresAt.epochSecond, again.state.value.linkCode?.expiresAt?.epochSecond)
        again.alreadyHaveAccount()
        again.ensureLinking()
        assertEquals(1, service.codes)
        // "Show new code" still makes a new one.
        again.startLinking()
        assertEquals(2, service.codes)
    }

    private fun approvalVm(service: AccountService = SampleAccountService(), now: () -> Long = { 0L }) = LinkApprovalViewModel(service, now)

    @Test
    fun approvalAsksTheScreenForTheBiometricAndSendsNothingUntilItSucceeds() {
        val service = SampleAccountService()
        val vm = approvalVm(service)
        vm.scanned("https://home.example.org/link/x#y")
        assertNotNull(vm.state.value.request)
        vm.approve()
        assertTrue(vm.state.value.awaitingBiometric)
        assertTrue(!vm.state.value.approved)
        vm.biometricResult(BiometricResult.Unavailable)
        assertEquals(AccountError.BiometricsUnavailable, vm.state.value.error)
        assertTrue(!vm.state.value.awaitingBiometric && !vm.state.value.busy)
        vm.approve()
        vm.biometricResult(BiometricResult.Success)
        assertTrue(vm.state.value.approved)
    }

    @Test
    fun cancellingTheBiometricShowsNoError() {
        val vm = approvalVm()
        vm.scanned("https://home.example.org/link/x#y")
        vm.approve()
        vm.biometricResult(BiometricResult.Cancelled)
        assertNull(vm.state.value.error)
        assertTrue(!vm.state.value.approved && !vm.state.value.busy && !vm.state.value.awaitingBiometric)
    }

    @Test
    fun otherBiometricFailuresAreNotCalledUnavailable() {
        val vm = approvalVm()
        vm.scanned("https://home.example.org/link/x#y")
        vm.approve()
        vm.biometricResult(BiometricResult.Failed)
        assertEquals(AccountError.Server, vm.state.value.error)
    }

    @Test
    fun scanningACodeForAnotherServerIsRefused() {
        val vm = approvalVm()
        vm.scanned("https://other.example.net/link/x#y")
        assertEquals(AccountError.WrongServer, vm.state.value.error)
    }

    @Test
    fun aRejectedCodeCanBeFollowedByTheRightOne() {
        var now = 0L
        val vm = approvalVm(now = { now })
        vm.scanned("https://other.example.net/link/x#y")
        assertEquals(1, vm.state.value.rejections)
        // The same wrong picture in the next frames is ignored for two seconds.
        vm.scanned("https://other.example.net/link/x#y")
        assertEquals(1, vm.state.value.rejections)
        vm.scanned("https://home.example.org/link/x#y")
        assertNotNull(vm.state.value.request)
        assertNull(vm.state.value.error)
        now += 1
    }

    @Test
    fun theSameWrongCodeIsReadAgainAfterTwoSeconds() {
        var now = 0L
        val vm = approvalVm(now = { now })
        vm.scanned("https://other.example.net/link/x#y")
        now = 2_500
        vm.scanned("https://other.example.net/link/x#y")
        assertEquals(2, vm.state.value.rejections)
    }

    @Test
    fun overlongScanIsRejectedBeforeParsing() {
        val vm = approvalVm()
        vm.scanned("https://home.example.org/link/" + "x".repeat(2100))
        assertEquals(AccountError.LinkExpired, vm.state.value.error)
        assertNull(vm.state.value.request)
    }
}
