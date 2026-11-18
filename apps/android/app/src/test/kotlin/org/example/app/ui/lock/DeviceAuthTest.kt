package org.example.app.ui.lock

import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
class DeviceAuthTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun setSecure(secure: Boolean) =
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(secure)

    @Test fun aPhoneWithoutAScreenLockIsNotAvailable() {
        setSecure(false)
        assertFalse(PlatformDeviceAuth(context).available)
    }

    @Test fun aPhoneWithAScreenLockIsAvailable() {
        setSecure(true)
        assertTrue(PlatformDeviceAuth(context).available)
    }

    @Test fun withoutAScreenLockTheSheetIsNeverShownAndTheAnswerIsUnavailable() {
        setSecure(false)
        var result: AuthResult? = null
        PlatformDeviceAuth(context).authenticate("Unlock") { result = it }
        assertEquals(AuthResult.Unavailable, result)
    }

    @Test fun aSystemThatRefusesTheSheetAnswersExactlyOnceUnavailableAndCanBeAskedAgain() {
        setSecure(true)
        var refuse = true
        val shown = mutableListOf<android.hardware.biometrics.BiometricPrompt.AuthenticationCallback>()
        val auth = PlatformDeviceAuth(context, show = { _, _, callback -> if (refuse) throw IllegalStateException("no service") else shown += callback })
        val answers = mutableListOf<AuthResult>()
        auth.authenticate("Unlock") { answers += it }
        assertEquals(listOf(AuthResult.Unavailable), answers)
        refuse = false
        auth.authenticate("Unlock") { answers += it }
        shown.single().onAuthenticationError(10, "cancelled")
        assertEquals(listOf(AuthResult.Unavailable, AuthResult.Cancelled), answers)
    }

    @Test fun aRequestWhileASheetIsOpenIsIgnoredAndAnErrorAfterASuccessIsNotASecondAnswer() {
        setSecure(true)
        val shown = mutableListOf<android.hardware.biometrics.BiometricPrompt.AuthenticationCallback>()
        val auth = PlatformDeviceAuth(context, show = { _, _, callback -> shown += callback })
        val answers = mutableListOf<AuthResult>()
        auth.authenticate("Unlock") { answers += it }
        auth.authenticate("Unlock") { answers += it }
        assertEquals(1, shown.size)
        shown.single().onAuthenticationSucceeded(null)
        shown.single().onAuthenticationError(5, "late")
        assertEquals(listOf(AuthResult.Success), answers)
    }

    @Test fun api29UsesDeviceCredentialAllowedAndApi30OnUsesAllowedAuthenticators() {
        assertEquals(CredentialRequest.DeviceCredentialAllowed, credentialRequestFor(29))
        assertEquals(CredentialRequest.AllowedAuthenticators, credentialRequestFor(30))
        assertEquals(CredentialRequest.AllowedAuthenticators, credentialRequestFor(35))
    }

    @Test fun everySystemErrorCodeIsCancelledNeverSuccess() {
        val codes = listOf(
            android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
            android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
            android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,
            android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT,
            android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS,
            android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT,
            android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_VENDOR, 0, -1, 99999,
        )
        for (code in codes) {
            val answers = mutableListOf<AuthResult>()
            PromptCallback { answers += it }.onAuthenticationError(code, "x")
            assertEquals(listOf(AuthResult.Cancelled), answers, "code $code")
        }
    }

    @Test fun aNonMatchingFingerAnswersNothingAndASuccessIsASuccess() {
        val answers = mutableListOf<AuthResult>()
        val callback = PromptCallback { answers += it }
        callback.onAuthenticationFailed()
        assertTrue(answers.isEmpty())
        callback.onAuthenticationSucceeded(null)
        assertEquals(listOf(AuthResult.Success), answers)
    }

    @Test fun theManifestDeclaresUseBiometricItself() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.USE_BIOMETRIC"))
    }

    @Test fun theSampleAuthAnswersInOrderAndCountsRequests() {
        val auth = SampleDeviceAuth(results = listOf(AuthResult.Cancelled))
        val answers = mutableListOf<AuthResult>()
        auth.authenticate("a") { answers += it }
        auth.authenticate("b") { answers += it }
        assertEquals(listOf(AuthResult.Cancelled, AuthResult.Success), answers)
        assertEquals(2, auth.requests)
    }
}
