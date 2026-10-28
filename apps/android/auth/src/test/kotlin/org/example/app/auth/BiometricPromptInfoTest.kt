package org.example.app.auth

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `PromptInfo.Builder.build()` throws for combinations the real API level does not support, so run on both. */
@RunWith(RobolectricTestRunner::class)
class BiometricPromptInfoTest {
    @Test @Config(sdk = [29]) fun api29BuildsEveryPromptTheGateCanShow() {
        val strong = BiometricGate.promptInfo(29, "t", "s", BIOMETRIC_STRONG, negative = "Use screen lock")
        assertEquals("Use screen lock", strong.negativeButtonText)
        val credential = BiometricGate.promptInfo(29, "t", null, DEVICE_CREDENTIAL, negative = null)
        assertEquals(true, credential.isDeviceCredentialAllowed)
    }

    @Test @Config(sdk = [34]) fun api34BuildsTheCombinedPrompt() {
        val info = BiometricGate.promptInfo(34, "t", null, BIOMETRIC_STRONG or DEVICE_CREDENTIAL, negative = null)
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, info.allowedAuthenticators)
    }
}
