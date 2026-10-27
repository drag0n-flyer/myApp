package org.example.app.ui.call

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.core.calls.CallEndReason
import org.example.app.core.calls.CallPhase
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.calls.SimulatedCallEngine
import org.example.app.core.calls.SimulatedNetwork
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.core.settings.CallSettings
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import org.example.app.ui.allLabels
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class CallScreenTest {
    @get:Rule val rule = createComposeRule()

    private val server = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    private val settings = CallSettings()
    private val store = SampleChatStore(SampleData.make(Instant.now()))
    private val engine = SimulatedCallEngine(server, settings, NetworkType.Wifi)
    private var scheme: androidx.compose.material3.ColorScheme? = null

    private val clock = ManualClock()
    private val owner = TestLifecycleOwner()

    private fun launch(
        video: Boolean = false,
        darkOutside: Boolean = false,
        ticking: Boolean = false,
        showSimulator: Boolean = false,
        canFlip: Boolean = false,
    ): CallViewModel {
        engine.start("riya", video)
        engine.advance(5.0)
        val vm = CallViewModel(engine, store, server, settings, simulator = engine, showSimulator = showSimulator)
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                AppTheme(darkTheme = darkOutside) {
                    CallScreen(vm, ticking = ticking, clock = clock, canFlipCamera = canFlip, onThemeResolved = { scheme = it })
                }
            }
        }
        return vm
    }

    private fun setLifecycle(state: Lifecycle.State) {
        rule.runOnUiThread { owner.registry.currentState = state }
        rule.waitForIdle()
    }

    @Test
    fun controlsSpeakerMuteVideoDataEndHaveLabels() {
        launch()
        for (label in listOf("Speaker", "Mute", "Video", "Data", "End")) {
            rule.onNode(hasContentDescription(label) and hasClickAction()).assertIsDisplayed()
        }
    }

    @Test
    fun muteTogglesAndRelabels() {
        launch()
        rule.onNodeWithContentDescription("Mute").performClick()
        rule.onNodeWithContentDescription("Unmute").assertIsDisplayed()
        assertTrue(engine.state.value!!.isMuted)
    }

    @Test
    fun showsNameTimerAndPills() {
        launch()
        rule.onNodeWithText("Riya").assertIsDisplayed()
        rule.onNodeWithText("00:05").assertIsDisplayed()
        rule.onNodeWithText("Good").assertIsDisplayed()
        rule.onNodeWithText("Auto · High quality", substring = true).assertIsDisplayed()
        rule.onNodeWithText("≈ 67 KB used").assertIsDisplayed()
    }

    @Test
    fun bannerSequenceMatchesTheDesign() {
        launch(video = true)
        engine.simulate(SimulatedNetwork.Weak)
        rule.onNodeWithText("Weak network: video lowered to save your voice quality").assertIsDisplayed()
        engine.simulate(SimulatedNetwork.VeryWeak)
        rule.onNodeWithText("Switched to audio only").assertIsDisplayed()
        engine.simulate(SimulatedNetwork.Lost)
        rule.onNodeWithText("Reconnecting…").assertIsDisplayed()
        engine.advance(30.0)
        rule.onNodeWithText("Call ended: connection lost").assertIsDisplayed()
    }

    @Test
    fun videoCallHidesFlipUntilACameraExists() {
        launch(video = true)
        rule.onNodeWithContentDescription("Flip camera").assertDoesNotExist()
        rule.onNodeWithContentDescription("Speaker").assertDoesNotExist()
        rule.onNodeWithContentDescription("Mute").assertIsDisplayed()
    }

    @Test
    fun videoCallShowsFlipWhenACameraExists() {
        launch(video = true, canFlip = true)
        rule.onNodeWithContentDescription("Flip camera").assertIsDisplayed()
    }

    @Test
    fun clockTicksEachSecondOnlyWhileStarted() {
        val vm = launch(ticking = true)
        setLifecycle(Lifecycle.State.RESUMED)
        clock.ticks.trySend(Unit)
        rule.waitForIdle()
        assertEquals(6.0, vm.call.value!!.elapsed)
        rule.onNodeWithText("00:06").assertIsDisplayed()

        setLifecycle(Lifecycle.State.CREATED)
        clock.ticks.trySend(Unit)
        rule.waitForIdle()
        assertEquals(6.0, vm.call.value!!.elapsed)

        setLifecycle(Lifecycle.State.STARTED)
        assertEquals(7.0, vm.call.value!!.elapsed)
    }

    @Test
    fun clockStopsOnceTheCallHasEnded() {
        val vm = launch(ticking = true)
        setLifecycle(Lifecycle.State.RESUMED)
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { vm.hangUp() }
        rule.mainClock.advanceTimeByFrame()
        val elapsed = vm.call.value!!.elapsed
        clock.ticks.trySend(Unit)
        rule.mainClock.advanceTimeBy(500)
        assertEquals(elapsed, vm.call.value!!.elapsed)
    }

    @Test
    fun finishedCallIsDismissedAfterTwoSeconds() {
        val vm = launch(ticking = true)
        setLifecycle(Lifecycle.State.RESUMED)
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { vm.hangUp() }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(1_900)
        assertTrue(vm.call.value != null, "still showing before 2 s")
        rule.mainClock.advanceTimeBy(200)
        assertEquals(null, vm.call.value)
    }

    @Test
    fun simulateMenuIsHiddenUnlessTheDebugFlagIsOn() {
        launch(showSimulator = false)
        rule.onNodeWithContentDescription("Simulate network").assertDoesNotExist()
    }

    @Test
    fun simulateMenuShowsWhenTheDebugFlagIsOn() {
        launch(showSimulator = true)
        rule.onNodeWithContentDescription("Simulate network").assertIsDisplayed()
    }

    @Test
    fun endHangsUpAndShowsCallEnded() {
        launch()
        rule.onNodeWithContentDescription("End").performClick()
        assertEquals(CallPhase.Ended(CallEndReason.HungUp), engine.state.value!!.phase)
        rule.onNodeWithText("Call ended").assertIsDisplayed()
    }

    @Test
    fun endedCallIsRecordedOnce() {
        launch()
        val before = store.chat("chat-riya")!!.messages.size
        rule.onNodeWithContentDescription("End").performClick()
        rule.waitForIdle()
        assertEquals(before + 1, store.chat("chat-riya")!!.messages.size)
    }

    @Test
    fun dataControlOpensTheSheet() {
        launch()
        rule.onNodeWithContentDescription("Data").performClick()
        rule.onNodeWithText("Data for this call").assertIsDisplayed()
        rule.onNodeWithText("High quality").performClick()
        assertEquals(ModePreference.Fixed(DataMode.High), engine.state.value!!.inCallPreference)
    }

    @Test
    fun alwaysUsesTheDarkSchemeEvenInALightApp() {
        launch(darkOutside = false)
        val colors = scheme!!
        assertTrue(colors.background.luminance() < 0.2f, "background ${colors.background}")
        assertTrue(colors.onBackground.luminance() > 0.5f)
        assertTrue(colors.background != Color.Unspecified)
    }

    @Test
    fun noEncryptionOrBannedWordsOnScreen() {
        launch(video = true)
        engine.simulate(SimulatedNetwork.Lost)
        val bad = Regex("encrypt|end-to-end|codec|kbps|relay|MLS|E2EE", RegexOption.IGNORE_CASE)
        assertEquals(emptyList(), rule.allLabels().filter { bad.containsMatchIn(it) })
    }

    @Test
    fun everyClickableHasALabel() {
        launch()
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        for (n in nodes) {
            val cd = n.config.getOrNullCd()
            assertTrue(!cd.isNullOrBlank(), "unlabelled clickable: ${n.config}")
        }
    }

    @Test
    fun incomingScreenOffersAnswerAndDecline() {
        engine.simulateIncoming("riya", video = false)
        val vm = CallViewModel(engine, store, server, settings)
        rule.setContent { AppTheme { IncomingCallScreen(vm) } }
        rule.onNodeWithText("Riya").assertIsDisplayed()
        rule.onNodeWithText("Incoming call").assertIsDisplayed()
        rule.onNodeWithContentDescription("Decline").assertIsDisplayed()
        rule.onNodeWithContentDescription("Answer").performClick()
        assertEquals(CallPhase.Active, engine.state.value!!.phase)
    }
}

private fun SemanticsConfiguration.getOrNullCd(): String? =
    getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

/** A clock the test drives: one `send` is one second. */
internal class ManualClock : CallClock {
    val ticks = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    override suspend fun awaitNextSecond() = ticks.receive()
}

internal class TestLifecycleOwner : androidx.lifecycle.LifecycleOwner {
    val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry
}
