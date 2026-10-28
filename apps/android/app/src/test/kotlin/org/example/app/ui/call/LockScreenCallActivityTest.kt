package org.example.app.ui.call

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.example.app.calls.android.CallExtras
import org.example.app.calls.android.CallInfo
import org.example.app.calls.android.CallNotifications
import org.example.app.calls.android.CallsRuntime
import org.example.app.calls.android.IncomingCallActivity
import org.example.app.core.calls.CallEngine
import org.example.app.core.calls.CallState
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.Contact
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class LockScreenCallActivityTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val call = CallInfo("c1", Contact("bob", "Bob", "bob"), video = false)

    private class RecordingEngine : CallEngine {
        val log = mutableListOf<String>()
        override val state = MutableStateFlow<CallState?>(null)
        override fun start(contactId: String, video: Boolean) = Unit
        override fun answer(video: Boolean) { log += "answer:$video" }
        override fun decline() { log += "decline" }
        override fun hangUp() = Unit
        override fun setMode(preference: ModePreference) = Unit
        override fun setMuted(on: Boolean) = Unit
        override fun setSpeaker(on: Boolean) = Unit
        override fun setVideo(on: Boolean) = Unit
        override fun setNetwork(type: NetworkType) = Unit
        override fun reset() = Unit
    }

    private val engine = RecordingEngine().also { CallsRuntime.engine = it }

    @After fun tearDown() { CallsRuntime.engine = null }

    private fun launch(): ActivityScenario<LockScreenCallActivity> =
        ActivityScenario.launch(
            Intent(context, LockScreenCallActivity::class.java)
                .setAction(IncomingCallActivity.ACTION_SHOW)
                .putExtras(CallExtras.bundle(call)),
        )

    @Test
    fun fullScreenIntentOpensTheLockScreenCallScreen() {
        val pending = CallNotifications.incoming(context, call, fullScreen = true).fullScreenIntent
        val target = context.packageManager.resolveActivity(shadowOf(pending).savedIntent, 0)?.activityInfo
        assertEquals(LockScreenCallActivity::class.java.name, target?.name)
        launch().use { scenario ->
            scenario.onActivity {
                assertTrue(shadowOf(it).showWhenLocked)
                assertTrue(shadowOf(it).turnScreenOn)
            }
        }
    }

    @Test
    fun showsCallerWithAnswerAndDecline() {
        launch().use {
            rule.onNodeWithText("Bob").assertExists()
            rule.onNodeWithText("Incoming call").assertExists()
            rule.onNodeWithContentDescription("Answer").performClick()
            assertEquals(listOf("answer:false"), engine.log)
        }
        launch().use {
            rule.onNodeWithContentDescription("Decline").performClick()
            assertEquals(listOf("answer:false", "decline"), engine.log)
        }
    }
}
