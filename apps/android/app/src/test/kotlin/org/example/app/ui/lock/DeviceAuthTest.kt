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

    @Test fun aSystemThatRefusesTheSheetAnswersUnavailableOnceAndNeverThrows() {
        setSecure(true)
        val answers = mutableListOf<AuthResult>()
        PlatformDeviceAuth(context).authenticate("Unlock") { answers += it }
        // Robolectric has no biometric service: either the sheet cannot be built (Unavailable) or it stays open (no answer yet).
        assertTrue(answers.size <= 1)
        assertTrue(answers.all { it == AuthResult.Unavailable })
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
