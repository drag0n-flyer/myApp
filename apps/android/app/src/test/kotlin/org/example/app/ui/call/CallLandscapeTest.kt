package org.example.app.ui.call

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import java.time.Instant
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

/** On a short landscape window the call controls run down the end edge and every one is reachable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land")
class CallLandscapeTest {
    @get:Rule val rule = createComposeRule()

    private val server = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    private val settings = CallSettings()
    private val store = SampleChatStore(SampleData.make(Instant.now()))
    private val engine = SimulatedCallEngine(server, settings, NetworkType.Wifi)

    @Test fun theControlsStandInAColumnAtTheEndAndTheNameShows() {
        engine.start("riya", false)
        engine.advance(5.0)
        val vm = CallViewModel(engine, store, server, settings, simulator = engine, showSimulator = false)
        rule.setContent { AppTheme { CallScreen(vm, ticking = false) } }
        rule.waitForIdle()
        rule.onNodeWithText("Riya").assertIsDisplayed()
        val mute = rule.onNodeWithContentDescription("Mute").getUnclippedBoundsInRoot()
        val end = rule.onNodeWithContentDescription("End").getUnclippedBoundsInRoot()
        // One column: the same horizontal position, the second below the first.
        assertTrue(mute.left == end.left && end.top > mute.top, "controls in a column: $mute $end")
        // Along the end edge, not the start.
        assertTrue(mute.left.value > 400f, "at the end: $mute")
    }

    @Test fun anIncomingVideoCallSaysSo() {
        rule.setContent { AppTheme { IncomingCallContent("Riya", onDecline = {}, onAnswer = {}, isVideo = true) } }
        rule.onNodeWithText("Incoming video call").assertIsDisplayed()
    }

    @Test fun anIncomingCallKeepsBothButtonsBesideTheName() {
        rule.setContent { AppTheme { IncomingCallContent("Riya", onDecline = {}, onAnswer = {}) } }
        rule.waitForIdle()
        rule.onNodeWithText("Riya").assertIsDisplayed()
        val decline = rule.onNodeWithContentDescription("Decline").getUnclippedBoundsInRoot()
        val answer = rule.onNodeWithContentDescription("Answer").getUnclippedBoundsInRoot()
        assertTrue(decline.left == answer.left && answer.top > decline.top, "buttons in a column: $decline $answer")
    }
}
