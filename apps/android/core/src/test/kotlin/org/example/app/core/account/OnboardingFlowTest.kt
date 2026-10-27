package org.example.app.core.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingFlowTest {
    private val invite = InviteLink("home.example.org", "ABCDE-FGH23")

    @Test fun androidCanCreateAnAccount() {
        assertTrue(OnboardingFlow(DevicePlatform.Android).canCreateAccount)
        assertTrue(OnboardingFlow(DevicePlatform.IPhone).canCreateAccount)
        assertFalse(OnboardingFlow(DevicePlatform.Mac).canCreateAccount)
    }

    @Test fun inviteLinkSkipsTheInviteStep() {
        val flow = OnboardingFlow(DevicePlatform.Android)
        flow.getStarted()
        flow.choose(ServerChoice.Invite(invite), requiresInvite = true)
        assertEquals(OnboardingStep.CreateAccount, flow.step)
    }

    @Test fun defaultServerThatRequiresInviteAsksForCode() {
        val flow = OnboardingFlow(DevicePlatform.Android)
        flow.getStarted()
        flow.choose(ServerChoice.DefaultServer, requiresInvite = true)
        assertEquals(OnboardingStep.InviteCode, flow.step)
        flow.inviteAccepted()
        assertEquals(OnboardingStep.CreateAccount, flow.step)
    }

    @Test fun openServerSkipsInviteStep() {
        val flow = OnboardingFlow(DevicePlatform.Android)
        flow.getStarted()
        flow.choose(ServerChoice.Address("chat.example.net"), requiresInvite = false)
        assertEquals(OnboardingStep.CreateAccount, flow.step)
    }

    @Test fun fullSignUpWalksEveryStepAndBackReturns() {
        val flow = OnboardingFlow(DevicePlatform.Android)
        flow.getStarted()
        flow.choose(ServerChoice.DefaultServer, requiresInvite = false)
        flow.accountSubmitted()
        assertEquals(OnboardingStep.VerifyEmail, flow.step)
        flow.emailVerified()
        assertEquals(OnboardingStep.CreatePasskey, flow.step)
        flow.passkeyCreated()
        assertEquals(OnboardingStep.Permissions, flow.step)
        flow.permissionsHandled()
        assertEquals(OnboardingStep.Done, flow.step)
        flow.back()
        assertEquals(OnboardingStep.Done, flow.step)
    }

    @Test fun backWalksHistory() {
        val flow = OnboardingFlow(DevicePlatform.Android)
        flow.getStarted()
        flow.back()
        assertEquals(OnboardingStep.Welcome, flow.step)
    }

    @Test fun macCannotGetStartedButCanLink() {
        val flow = OnboardingFlow(DevicePlatform.Mac)
        flow.getStarted()
        assertEquals(OnboardingStep.Welcome, flow.step)
        flow.alreadyHaveAccount()
        assertEquals(OnboardingStep.LinkDevice, flow.step)
        flow.noOldDevice()
        assertEquals(OnboardingStep.LinkDevice, flow.step)
    }

    @Test fun lostDeviceRecovers() {
        val flow = OnboardingFlow(DevicePlatform.Android)
        flow.alreadyHaveAccount()
        flow.noOldDevice()
        assertEquals(OnboardingStep.LostDevice, flow.step)
        flow.recovered()
        assertEquals(OnboardingStep.Done, flow.step)
    }

    @Test fun platformWireValues() {
        assertEquals("iphone", DevicePlatform.IPhone.wire)
        assertEquals("mac", DevicePlatform.Mac.wire)
        assertEquals("android", DevicePlatform.Android.wire)
        assertEquals(DevicePlatform.Android, DevicePlatform.fromWire("android"))
        assertEquals(null, DevicePlatform.fromWire("windows"))
    }
}
