package org.example.app

import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityLockTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun launch(lock: Boolean) = ActivityScenario.launch<MainActivity>(
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SAMPLE_DATA, true).putExtra(MainActivity.EXTRA_APP_LOCK, lock),
    )

    private fun MainActivity.secure() = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    @Test fun theWindowIsSecureWhileTheLockIsOn() {
        launch(lock = true).use { it.onActivity { a -> assertTrue(a.secure()) } }
    }

    @Test fun theWindowIsNotSecureWithTheLockOff() {
        launch(lock = false).use { it.onActivity { a -> assertFalse(a.secure()) } }
    }

    @Test fun theFlagFollowsTheSettingBothWays() {
        launch(lock = false).use { scenario ->
            var graph: AppGraph? = null
            scenario.onActivity { a ->
                graph = MainActivity::class.java.getDeclaredField("graph").also { it.isAccessible = true }.get(a) as AppGraph
                graph!!.settingsStore.update { it.copy(appLock = true) }
            }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            scenario.onActivity { a -> assertTrue(a.secure()) }
            graph!!.settingsStore.update { it.copy(appLock = false) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            scenario.onActivity { a -> assertFalse(a.secure()) }
        }
    }

    @Test fun aRotationKeepsTheFlagAndTheLock() {
        launch(lock = true).use { scenario ->
            scenario.recreate()
            scenario.onActivity { a -> assertTrue(a.secure()) }
        }
    }
}
