package org.example.app.auth

import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
import androidx.fragment.app.FragmentActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBiometricManager
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiometricGateTest {
    @Test fun noBiometricsIsUnavailable() = runTest {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).create().get()
        val shadow: ShadowBiometricManager = Shadow.extract(activity.getSystemService(android.hardware.biometrics.BiometricManager::class.java))
        shadow.setCanAuthenticate(false)
        assertEquals(BiometricGate.Result.Unavailable, BiometricGate.authenticate(activity, "Confirm"))
    }

    @Test fun noneEnrolledIsUnavailableOnEveryApi() = runTest {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).create().get()
        for (sdk in listOf(29, 30, 34)) {
            assertEquals(
                BiometricGate.Result.Unavailable,
                BiometricGate.authenticate(activity, "Confirm", sdk = sdk, canAuthenticate = { BIOMETRIC_ERROR_NONE_ENROLLED }),
                "sdk $sdk",
            )
        }
    }
}
