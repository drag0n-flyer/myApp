package org.example.app.auth

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import androidx.biometric.BiometricPrompt
import kotlin.test.Test
import kotlin.test.assertEquals

class BiometricPolicyTest {
    private val both = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    @Test fun api30UsesStrongOrDeviceCredentialInOnePrompt() {
        val plan = BiometricGate.plan(30, deviceSecure = false) { if (it == both) BIOMETRIC_SUCCESS else error("asked $it") }
        assertEquals(BiometricGate.Plan.Combined(both), plan)
    }

    // API 29 cannot ask the manager about DEVICE_CREDENTIAL (it answers "unsupported"), so the screen lock
    // comes from KeyguardManager.isDeviceSecure, passed as `deviceSecure`.
    private val unsupported = BIOMETRIC_ERROR_UNSUPPORTED

    @Test fun api29StrongWithAScreenLockOffersTheScreenLock() {
        val plan = BiometricGate.plan(29, deviceSecure = true) { if (it == BIOMETRIC_STRONG) BIOMETRIC_SUCCESS else unsupported }
        assertEquals(BiometricGate.Plan.StrongThenCredential(credentialFallback = true), plan)
    }

    @Test fun api29StrongWithoutAScreenLockHasNoFallback() {
        val plan = BiometricGate.plan(29, deviceSecure = false) { if (it == BIOMETRIC_STRONG) BIOMETRIC_SUCCESS else unsupported }
        assertEquals(BiometricGate.Plan.StrongThenCredential(credentialFallback = false), plan)
    }

    @Test fun api29PinOnlyPhoneGetsTheScreenLockPrompt() {
        val plan = BiometricGate.plan(29, deviceSecure = true) { if (it == BIOMETRIC_STRONG) BIOMETRIC_ERROR_NONE_ENROLLED else unsupported }
        assertEquals(BiometricGate.Plan.CredentialOnly, plan)
    }

    @Test fun api29WithNothingSetUpIsUnavailable() {
        val plan = BiometricGate.plan(29, deviceSecure = false) { if (it == BIOMETRIC_STRONG) BIOMETRIC_ERROR_NONE_ENROLLED else unsupported }
        assertEquals(BiometricGate.Plan.Blocked(BiometricGate.Result.Unavailable), plan)
    }

    @Test fun api29NeverAsksTheManagerAboutDeviceCredential() {
        BiometricGate.plan(29, deviceSecure = true) { check(it == BIOMETRIC_STRONG) { "asked $it" }; BIOMETRIC_SUCCESS }
    }

    @Test fun api30PinOnlyPhoneUsesTheCombinedPrompt() {
        val plan = BiometricGate.plan(30, deviceSecure = true) { if (it == both) BIOMETRIC_SUCCESS else error("asked $it") }
        assertEquals(BiometricGate.Plan.Combined(both), plan)
    }

    @Test fun unavailableOnlyWhenNothingIsEnrolled() {
        assertEquals(BiometricGate.Plan.Blocked(BiometricGate.Result.Unavailable), BiometricGate.plan(34, deviceSecure = false) { BIOMETRIC_ERROR_NONE_ENROLLED })
        assertEquals(BiometricGate.Plan.Blocked(BiometricGate.Result.Unavailable), BiometricGate.plan(34, deviceSecure = false) { BIOMETRIC_ERROR_NO_HARDWARE })
        assertEquals(BiometricGate.Plan.Blocked(BiometricGate.Result.Unavailable), BiometricGate.plan(29, deviceSecure = false) { BIOMETRIC_ERROR_NONE_ENROLLED })
        assertEquals(BiometricGate.Plan.Blocked(BiometricGate.Result.Failed), BiometricGate.plan(34, deviceSecure = false) { BIOMETRIC_ERROR_HW_UNAVAILABLE })
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
