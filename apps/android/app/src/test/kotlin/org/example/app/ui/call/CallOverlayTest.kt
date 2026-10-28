package org.example.app.ui.call

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.WindowCompat
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.calls.SimulatedCallEngine
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.core.settings.CallSettings
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A call screen sits over the rest of the app (the chat behind it): it owns Back and touch while it shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class CallOverlayTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val server = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    private val settings = CallSettings()
    private val store = SampleChatStore(SampleData.make(Instant.now()))
    private val engine = SimulatedCallEngine(server, settings, NetworkType.Wifi)
    private val vm = CallViewModel(engine, store, server, settings, simulator = engine)
    private var backsBehind = 0
    private var tapsBehind = 0

    private fun show(overlay: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            AppTheme {
                Box(Modifier.fillMaxSize()) {
                    BackHandler { backsBehind++ }
                    Box(Modifier.fillMaxSize().clickable { tapsBehind++ })
                    overlay()
                }
            }
        }
    }

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun tapCorner() {
        rule.onRoot().performTouchInput { click(Offset(4f, 4f)) }
        rule.waitForIdle()
    }

    private fun startCall() { engine.start("riya", false); engine.advance(5.0) }

    @Test
    fun backDoesNothingBehindTheCallWhenThereIsNoBanner() {
        startCall()
        show { CallScreen(vm, ticking = false) }
        pressBack()
        assertEquals(0, backsBehind)
        assertNotNull(vm.call.value)
        assertFalse(vm.call.value!!.isEnded)
    }

    @Test
    fun backMinimisesWhenThereIsAnInCallBanner() {
        startCall()
        var minimised = 0
        show { CallScreen(vm, ticking = false, onMinimise = { minimised++ }) }
        pressBack()
        assertEquals(1, minimised)
        assertEquals(0, backsBehind)
    }

    @Test
    fun touchesOnTheCallScreenDoNotReachTheChatBehind() {
        startCall()
        show { CallScreen(vm, ticking = false) }
        tapCorner()
        assertEquals(0, tapsBehind)
    }

    @Test
    fun theIncomingScreenAlsoOwnsBackAndTouch() {
        engine.simulateIncoming("riya", video = false)
        show { IncomingCallScreen(vm) }
        pressBack()
        tapCorner()
        assertEquals(0, backsBehind)
        assertEquals(0, tapsBehind)
        assertNotNull(vm.call.value)
    }

    @Test
    fun withoutTheCallScreenTheChatBehindGetsTouchAndBack() {
        show {}
        pressBack()
        tapCorner()
        assertEquals(1, backsBehind)
        assertEquals(1, tapsBehind)
    }

    @Test
    fun statusBarIconsAreLightWhileTheCallShowsThenRestored() {
        startCall()
        val window = rule.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        rule.runOnUiThread { controller.isAppearanceLightStatusBars = true }
        var showing by mutableStateOf(true)
        rule.setContent { AppTheme { if (showing) CallScreen(vm, ticking = false) } }
        rule.waitForIdle()
        assertFalse(controller.isAppearanceLightStatusBars, "light icons while the call shows")
        showing = false
        rule.waitForIdle()
        assertTrue(controller.isAppearanceLightStatusBars, "restored after the call screen leaves")
    }
}
