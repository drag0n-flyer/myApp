package org.example.app.auth

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Real BiometricManager. A fresh CI emulator has no fingerprint and no screen lock, so nothing is set up. */
class BiometricGateInstrumentedTest {
    @Test fun freshEmulatorHasNothingSetUp() {
        val manager = BiometricManager.from(ApplicationProvider.getApplicationContext())
        val plan = BiometricGate.plan(Build.VERSION.SDK_INT, manager::canAuthenticate)
        assertIs<BiometricGate.Plan.Blocked>(plan)
        assertEquals(BiometricGate.Result.Unavailable, plan.result)
    }
}
