package org.example.app.ui.call

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

/** The camera rules of a video call, on the simulated engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class CallVideoScreenTest {
    @get:Rule val rule = createComposeRule()

    private val server = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    private val store = SampleChatStore(SampleData.make(Instant.now()))
    private val engine = SimulatedCallEngine(server, CallSettings(), NetworkType.Wifi)

    private fun launch(access: CameraAccess): CallViewModel {
        engine.start("riya", true)
        engine.advance(5.0)
        val vm = CallViewModel(engine, store, server, CallSettings(), simulator = engine)
        rule.setContent { AppTheme { CallScreen(vm, ticking = false, cameraAccess = access) } }
        rule.waitForIdle()
        return vm
    }

    @Test
    fun grantedCameraShowsTheStageAndNoMessage() {
        var asked = 0
        launch(CameraAccess(isGranted = { true }, request = { asked++ }))
        rule.onNodeWithContentDescription("Your video").assertIsDisplayed()
        rule.onNodeWithText("Camera access is off. Turn it on in Settings.").assertDoesNotExist()
        assertEquals(0, asked)
    }

    @Test
    fun refusedCameraContinuesAsAudioWithTheMessage() {
        var asked = 0
        launch(CameraAccess(isGranted = { false }, request = { result -> asked++; result(false) }))
        assertEquals(1, asked)
        rule.onNodeWithText("Camera access is off. Turn it on in Settings.").assertIsDisplayed()
        // The call is still up, now without video.
        assertFalse(engine.state.value!!.isVideoOn)
        assertFalse(engine.state.value!!.isEnded)
        rule.onNodeWithContentDescription("Your video").assertDoesNotExist()
    }

    @Test
    fun allowingTheCameraKeepsVideoOn() {
        var held = false
        launch(CameraAccess(isGranted = { held }, request = { result -> held = true; result(true) }))
        assertTrue(engine.state.value!!.isVideoOn)
        rule.onNodeWithContentDescription("Your video").assertIsDisplayed()
        rule.onNodeWithText("Camera access is off. Turn it on in Settings.").assertDoesNotExist()
    }
}
