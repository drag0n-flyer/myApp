package org.example.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FullScreenIntentRefreshTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun permissionIsCheckedAgainWhenTheAppResumes() {
        var granted = false
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        var seen: Boolean? = null
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val allowed by rememberRefreshedOnResume { granted }
                seen = allowed
            }
        }
        rule.waitForIdle()
        assertEquals(false, seen)
        granted = true // the user grants it in system settings, then comes back
        rule.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        rule.waitForIdle()
        assertEquals(true, seen)
    }
}
