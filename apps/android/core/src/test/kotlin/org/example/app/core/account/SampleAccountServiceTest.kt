package org.example.app.core.account

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SampleAccountServiceTest {
    private val service = SampleAccountService(DevicePlatform.Android)

    @Test fun signUpHappyPath() = runTest {
        service.connect("home.example.org")
        assertTrue(service.redeemInvite(SampleAccountService.SAMPLE_INVITE_CODE))
        service.startSignUp("newperson", "a@b.co")
        service.verifyEmail(SampleAccountService.SAMPLE_EMAIL_CODE)
        service.finishSignUp()
        assertTrue(service.isSignedIn)
    }

    @Test fun errors() = runTest {
        assertEquals(UsernameCheck.Taken, service.checkUsername("riya"))
        assertEquals(UsernameCheck.Invalid, service.checkUsername("Ab"))
        assertEquals(UsernameCheck.Available, service.checkUsername("fresh_name"))
        assertEquals(AccountError.InviteInvalid, assertFailsWith<AccountError> { service.redeemInvite("ZZZZZ-ZZZZ2") })
        assertEquals(AccountError.UsernameTaken, assertFailsWith<AccountError> { service.startSignUp("riya", "a@b.co") })
        assertEquals(AccountError.InvalidEmail, assertFailsWith<AccountError> { service.startSignUp("fresh", "nope") })
        assertEquals(AccountError.CodeInvalid, assertFailsWith<AccountError> { service.verifyEmail("000000") })
        assertEquals(AccountError.TooManyAttempts(30), AccountError.TooManyAttempts(30))
    }

    @Test fun devicesStartWithThisPhoneAndIPhone() = runTest {
        val devices = service.devices()
        assertEquals(listOf(DevicePlatform.Android, DevicePlatform.IPhone), devices.map { it.platform })
        service.removeDevice("this")
        assertEquals(2, service.devices().size)
        service.removeDevice("iphone")
        assertEquals(1, service.devices().size)
    }

    @Test fun sampleInviteCodeIsValidFormat() {
        assertEquals(SampleAccountService.SAMPLE_INVITE_CODE, InviteLink.parse(SampleAccountService.SAMPLE_INVITE_CODE)?.code)
    }

    @Test fun linkChecks() = runTest {
        assertEquals(AccountError.LinkExpired, assertFailsWith<AccountError> { service.linkRequest("garbage") })
        assertEquals(AccountError.WrongServer, assertFailsWith<AccountError> { service.linkRequest("https://other.example/link/x#1") })
        val request = service.linkRequest(SampleAccountService.SAMPLE_LINK_TEXT)
        service.approveLink(request)
        assertEquals(3, service.devices().size)
    }

    @Test fun signOutLocally() = runTest {
        service.finishSignUp()
        service.signOutLocally()
        assertEquals(false, service.isSignedIn)
    }
}
