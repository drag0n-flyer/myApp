package org.example.app

import android.content.Intent
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * App lock on sample data, on a real (emulated) device. The sample's device authentication is the test hook: its first
 * (automatic) prompt is dismissed and the Unlock button passes. The real fingerprint sheet is not exercised here.
 */
@RunWith(AndroidJUnit4::class)
class AppLockTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun close() { scenario?.close() }

    @Test
    fun relaunchShowsTheCoverAndUnlockRevealsTheChatList() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SAMPLE_DATA, true)
            .putExtra(MainActivity.EXTRA_APP_LOCK, true)
        scenario = ActivityScenario.launch(intent)
        rule.onNodeWithText("Unlock").assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(hasText("Riya")).fetchSemanticsNodes().size)
        scenario!!.onActivity { a ->
            assertTrue((a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0)
        }
        rule.onNodeWithText("Unlock").performClick()
        rule.onNodeWithText("Riya").assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(hasText("Unlock")).fetchSemanticsNodes().size)
    }
}
