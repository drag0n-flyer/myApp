package org.example.app.ui.onboarding

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import androidx.biometric.BiometricPrompt
import kotlin.test.Test
import kotlin.test.assertEquals

class BiometricPolicyTest {
    private val both = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    @Test fun api30UsesStrongOrDeviceCredentialInOnePrompt() {
        val plan = BiometricPolicy.plan(30) { if (it == both) BIOMETRIC_SUCCESS else error("asked $it") }
        assertEquals(BiometricPlan.Combined(both), plan)
    }

    @Test fun api29UsesStrongThenOffersTheScreenLock() {
        val plan = BiometricPolicy.plan(29) { BIOMETRIC_SUCCESS }
        assertEquals(BiometricPlan.StrongThenCredential(credentialFallback = true), plan)
    }

    @Test fun api29StrongWithoutAScreenLockHasNoFallback() {
        val plan = BiometricPolicy.plan(29) { if (it == BIOMETRIC_STRONG) BIOMETRIC_SUCCESS else BIOMETRIC_ERROR_NONE_ENROLLED }
        assertEquals(BiometricPlan.StrongThenCredential(credentialFallback = false), plan)
    }

    @Test fun api29WithoutStrongFallsBackToTheScreenLock() {
        val plan = BiometricPolicy.plan(29) { if (it == DEVICE_CREDENTIAL) BIOMETRIC_SUCCESS else BIOMETRIC_ERROR_NONE_ENROLLED }
        assertEquals(BiometricPlan.CredentialOnly, plan)
    }

    @Test fun unavailableOnlyWhenNothingIsEnrolled() {
        assertEquals(BiometricPlan.Blocked(BiometricResult.Unavailable), BiometricPolicy.plan(34) { BIOMETRIC_ERROR_NONE_ENROLLED })
        assertEquals(BiometricPlan.Blocked(BiometricResult.Unavailable), BiometricPolicy.plan(34) { BIOMETRIC_ERROR_NO_HARDWARE })
        assertEquals(BiometricPlan.Blocked(BiometricResult.Unavailable), BiometricPolicy.plan(29) { BIOMETRIC_ERROR_NONE_ENROLLED })
        assertEquals(BiometricPlan.Blocked(BiometricResult.Failed), BiometricPolicy.plan(34) { BIOMETRIC_ERROR_HW_UNAVAILABLE })
    }

    @Test fun cancelsMapToCancelled() {
        for (code in listOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED)) {
            assertEquals(BiometricResult.Cancelled, BiometricPolicy.resultForError(code), "$code")
        }
    }

    @Test fun noBiometricOrCredentialIsUnavailableAndTheRestFail() {
        assertEquals(BiometricResult.Unavailable, BiometricPolicy.resultForError(BiometricPrompt.ERROR_NO_BIOMETRICS))
        assertEquals(BiometricResult.Unavailable, BiometricPolicy.resultForError(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL))
        assertEquals(BiometricResult.Unavailable, BiometricPolicy.resultForError(BiometricPrompt.ERROR_HW_NOT_PRESENT))
        assertEquals(BiometricResult.Failed, BiometricPolicy.resultForError(BiometricPrompt.ERROR_LOCKOUT))
        assertEquals(BiometricResult.Failed, BiometricPolicy.resultForError(BiometricPrompt.ERROR_HW_UNAVAILABLE))
        assertEquals(BiometricResult.Failed, BiometricPolicy.resultForError(BiometricPrompt.ERROR_TIMEOUT))
    }
}
