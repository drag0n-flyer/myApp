package org.example.app.ui.call

import androidx.compose.ui.graphics.Color
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

    private fun launch(video: Boolean = false, darkOutside: Boolean = false): CallViewModel {
        engine.start("riya", video)
        engine.advance(5.0)
        val vm = CallViewModel(engine, store, server, settings)
        rule.setContent {
            AppTheme(darkTheme = darkOutside) { CallScreen(vm, ticking = false, onThemeResolved = { scheme = it }) }
        }
        return vm
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
    fun videoCallOnShowsFlipInsteadOfSpeaker() {
        launch(video = true)
        rule.onNodeWithContentDescription("Flip camera").assertIsDisplayed()
        rule.onNodeWithContentDescription("Speaker").assertDoesNotExist()
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
