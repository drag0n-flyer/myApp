package org.example.app.ui.call

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.example.app.calls.android.media.CallVideo
import org.example.app.calls.android.media.CallVideoSurface
import org.example.app.calls.android.media.VideoFeed
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
import org.webrtc.EglBase
import org.webrtc.VideoSink

/**
 * A video call on the simulated engine, on a real device or emulator: the video stage shows, the flip control follows
 * the camera count, and renderers (real `SurfaceViewRenderer`s on a real EGL context) are attached and released once
 * per call, so two calls in a row leave none behind.
 */
@RunWith(AndroidJUnit4::class)
class CallVideoInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    private val server = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    private val store = SampleChatStore(SampleData.make(Instant.now()))
    private val granted = CameraAccess(isGranted = { true }, request = { it(true) })

    private class CountingFeed : VideoFeed {
        val attached = mutableListOf<VideoSink>()
        var attaches = 0
        var detaches = 0
        override fun attach(sink: VideoSink): Boolean { attaches++; attached += sink; return true }
        override fun detach(sink: VideoSink) { detaches++; attached -= sink }
    }

    private class FakeSurface(override val cameraCount: Int, override val eglContext: EglBase.Context?) : CallVideoSurface {
        override val remote = CountingFeed()
        override val local = CountingFeed()
        override val remoteVideoOn: StateFlow<Boolean> = MutableStateFlow(true)
        override val frontCamera: StateFlow<Boolean> = MutableStateFlow(true)
        override val cameraProblem: StateFlow<Boolean> = MutableStateFlow(false)
        var flips = 0
        override fun flipCamera() { flips++ }
        override fun cameraPermissionGranted() = Unit
    }

    private fun startVideoCall(): Pair<SimulatedCallEngine, CallViewModel> {
        val engine = SimulatedCallEngine(server, CallSettings(), NetworkType.Wifi)
        engine.start("riya", true)
        engine.advance(5.0)
        return engine to CallViewModel(engine, store, server, CallSettings(), simulator = engine)
    }

    @Test
    fun videoCallShowsTheStageAndNoFlipWithOneCamera() {
        val surface = FakeSurface(cameraCount = 1, eglContext = null)
        CallVideo.publish(surface)
        val (_, vm) = startVideoCall()
        rule.setContent { AppTheme { CallScreen(vm, ticking = false, cameraAccess = granted) } }
        rule.onNodeWithContentDescription("Your video").assertIsDisplayed()
        rule.onNodeWithContentDescription("Flip camera").assertDoesNotExist()
        CallVideo.clear(surface)
    }

    @Test
    fun flipShowsWithTwoCamerasAndFlips() {
        val surface = FakeSurface(cameraCount = 2, eglContext = null)
        CallVideo.publish(surface)
        val (_, vm) = startVideoCall()
        rule.setContent { AppTheme { CallScreen(vm, ticking = false, cameraAccess = granted) } }
        rule.onNodeWithContentDescription("Flip camera").assertIsDisplayed()
        rule.onNodeWithContentDescription("Flip camera").performClick()
        assertEquals(1, surface.flips)
        CallVideo.clear(surface)
    }

    @Test
    fun renderersAreAttachedAndReleasedPerCallWithNoLeakAcrossTwoCalls() {
        val egl = EglBase.create()
        try {
            val first = FeedPair()
            val second = FeedPair()
            var feeds: FeedPair? by mutableStateOf(first)
            rule.setContent { AppTheme { feeds?.let { VideoStage(it.remote, it.local, "Riya", egl = egl.eglBaseContext) } } }
            rule.waitForIdle()
            assertEquals(1, first.remote.attaches)
            assertEquals(1, first.local.attaches)

            feeds = null // the first call ended: the stage leaves composition
            rule.waitForIdle()
            assertEquals(1, first.remote.detaches)
            assertEquals(1, first.local.detaches)
            assertTrue(first.remote.attached.isEmpty() && first.local.attached.isEmpty())

            feeds = second // the next call: fresh renderers on a fresh feed
            rule.waitForIdle()
            assertEquals(1, second.remote.attaches)
            assertEquals(1, first.remote.attaches) // the first feed was not touched again
            feeds = null
            rule.waitForIdle()
            assertEquals(1, second.remote.detaches)
            assertTrue(second.remote.attached.isEmpty() && second.local.attached.isEmpty())
        } finally {
            egl.release()
        }
    }

    private class FeedPair {
        val remote = CountingFeed()
        val local = CountingFeed()
    }
}
