package org.example.app.auth

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.After

/**
 * Real BiometricManager and KeyguardManager. CI emulators (API 29 and 36) start with no fingerprint and no
 * screen lock; the PIN is set and removed through the shell and always removed again.
 */
class BiometricGateInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val keyguard get() = context.getSystemService(KeyguardManager::class.java)

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { it.fileDescriptor }
        Thread.sleep(500)
    }

    @After fun removePin() = shell("locksettings clear --old 1234")

    private fun currentPlan() = BiometricGate.plan(
        Build.VERSION.SDK_INT,
        keyguard.isDeviceSecure,
        BiometricManager.from(context)::canAuthenticate,
    )

    @Test fun freshEmulatorHasNothingSetUp() {
        shell("locksettings clear --old 1234")
        val plan = currentPlan()
        assertIs<BiometricGate.Plan.Blocked>(plan)
        assertEquals(BiometricGate.Result.Unavailable, plan.result)
    }

    /** The A8 ruling on a phone with a PIN and no fingerprint: a working screen-lock prompt on every API level. */
    @Test fun pinOnlyPhoneGetsAWorkingScreenLockPrompt() {
        shell("locksettings set-pin 1234")
        val plan = currentPlan()
        val authenticators = if (Build.VERSION.SDK_INT >= 30) {
            assertEquals(BiometricGate.Plan.Combined(BIOMETRIC_STRONG or DEVICE_CREDENTIAL), plan)
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        } else {
            assertEquals(BiometricGate.Plan.CredentialOnly, plan)
            DEVICE_CREDENTIAL
        }
        // build() throws if the combination is not supported on this API level.
        BiometricGate.promptInfo(Build.VERSION.SDK_INT, "Confirm", null, authenticators, negative = null)
    }
}
