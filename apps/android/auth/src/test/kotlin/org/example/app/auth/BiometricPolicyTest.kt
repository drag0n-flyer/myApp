package org.example.app.auth

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
        val plan = BiometricGate.plan(30) { if (it == both) BIOMETRIC_SUCCESS else error("asked $it") }
        assertEquals(BiometricGate.Plan.Combined(both), plan)
    }

    @Test fun api29UsesStrongThenOffersTheScreenLock() {
        val plan = BiometricGate.plan(29) { BIOMETRIC_SUCCESS }
        assertEquals(BiometricGate.Plan.StrongThenCredential(credentialFallback = true), plan)
    }

    @Test fun api29StrongWithoutAScreenLockHasNoFallback() {
        val plan = BiometricGate.plan(29) { if (it == BIOMETRIC_STRONG) BIOMETRIC_SUCCESS else BIOMETRIC_ERROR_NONE_ENROLLED }
        assertEquals(BiometricGate.Plan.StrongThenCredential(credentialFallback = false), plan)
    }

    @Test fun api29WithoutStrongFallsBackToTheScreenLock() {
        val plan = BiometricGate.plan(29) { if (it == DEVICE_CREDENTIAL) BIOMETRIC_SUCCESS else BIOMETRIC_ERROR_NONE_ENROLLED }
        assertEquals(BiometricGate.Plan.CredentialOnly, plan)
    }

    @Test fun unavailableOnlyWhenNothingIsEnrolled() {
        assertEquals(BiometricGate.Plan.Blocked(BiometricGate.Result.Unavailable), BiometricGate.plan(34) { BIOMETRIC_ERROR_NONE_ENROLLED })
        assertEquals(BiometricGate.Plan.Blocked(BiometricGate.Result.Unavailable), BiometricGate.plan(34) { BIOMETRIC_ERROR_NO_HARDWARE })
        assertEquals(BiometricGate.Plan.Blocked(BiometricGate.Result.Unavailable), BiometricGate.plan(29) { BIOMETRIC_ERROR_NONE_ENROLLED })
        assertEquals(BiometricGate.Plan.Blocked(BiometricGate.Result.Failed), BiometricGate.plan(34) { BIOMETRIC_ERROR_HW_UNAVAILABLE })
    }

    @Test fun cancelsMapToCancelled() {
        for (code in listOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED)) {
            assertEquals(BiometricGate.Result.Cancelled, BiometricGate.resultForError(code), "$code")
        }
    }

    @Test fun noBiometricOrCredentialIsUnavailableAndTheRestFail() {
        assertEquals(BiometricGate.Result.Unavailable, BiometricGate.resultForError(BiometricPrompt.ERROR_NO_BIOMETRICS))
        assertEquals(BiometricGate.Result.Unavailable, BiometricGate.resultForError(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL))
        assertEquals(BiometricGate.Result.Unavailable, BiometricGate.resultForError(BiometricPrompt.ERROR_HW_NOT_PRESENT))
        assertEquals(BiometricGate.Result.Failed, BiometricGate.resultForError(BiometricPrompt.ERROR_LOCKOUT))
        assertEquals(BiometricGate.Result.Failed, BiometricGate.resultForError(BiometricPrompt.ERROR_HW_UNAVAILABLE))
        assertEquals(BiometricGate.Result.Failed, BiometricGate.resultForError(BiometricPrompt.ERROR_TIMEOUT))
    }
}
