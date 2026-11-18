package org.example.app.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.center
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.core.chats.VoiceGesture
import org.example.app.ui.media.CaptureState
import org.example.app.ui.media.VoiceEngine
import org.example.app.ui.media.VoiceEngineFactory
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceComposerTest {
    @get:Rule val rule = createComposeRule()

    private val log = mutableListOf<String>()

    private fun ui(state: CaptureState = CaptureState.Idle, hint: Boolean = false, engines: VoiceEngineFactory = VoiceEngineFactory { NoEngine }) = VoiceUi(
        state = state, hint = hint,
        onPress = { log += "press" }, onDrag = { dx, dy -> log += "drag ${dx.toInt()} ${dy.toInt()}" }, onLift = { log += "lift" },
        onStop = { log += "stop" }, onDelete = { log += "delete" }, onSend = { log += "send" },
        previewEngines = engines,
    )

    private object NoEngine : VoiceEngine {
        override fun prepare(file: File, listener: VoiceEngine.Listener) = Unit
        override fun start() = Unit
        override fun pause() = Unit
        override val positionMs = 0
        override fun release() = Unit
    }

    private fun show(voice: VoiceUi, onRecordVoice: () -> Unit = { log += "activate" }) = rule.setContent {
        AppTheme {
            Composer(enabled = true, text = "", onTextChange = {}, onSend = {}, onAttach = {}, onRecordVoice = onRecordVoice, voice = voice)
        }
    }

    @Test fun holdingTheMicrophoneSendsPressDragAndLift() {
        show(ui())
        rule.onNodeWithContentDescription("Record voice message").performTouchInput {
            down(center)
            moveBy(Offset(-300f, 0f))
            up()
        }
        assertEquals("press", log.first())
        assertTrue(log.any { it.startsWith("drag -") }, "drag toward the leading edge is negative: $log")
        assertEquals("lift", log.last())
    }

    @Test fun activatingTheMicrophoneFromAccessibilityStartsALockedRecording() {
        show(ui())
        rule.onNodeWithContentDescription("Record voice message").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("activate"), log)
    }

    @Test fun theHintShowsWhileSet() {
        show(ui(hint = true))
        rule.onNodeWithText("Hold to record, release to send").assertIsDisplayed()
    }

    @Test fun theHoldBarShowsTimeAndSlideToCancelAndKeepsTheMicrophone() {
        show(ui(CaptureState.Recording(7.0, levels = listOf(0.2f, 0.9f))))
        rule.onNodeWithText("0:07").assertIsDisplayed()
        rule.onNodeWithText("Slide to cancel").assertIsDisplayed()
        rule.onNodeWithContentDescription("Record voice message").assertIsDisplayed()
        // The message field is gone while recording.
        rule.onNodeWithText("Message").assertDoesNotExist()
    }

    @Test fun theLockedBarHasDeleteStopAndSend() {
        show(ui(CaptureState.Recording(65.4, phase = VoiceGesture.State.locked)))
        rule.onNodeWithText("1:05").assertIsDisplayed()
        rule.onNodeWithContentDescription("Recording locked").assertIsDisplayed()
        rule.onNodeWithContentDescription("Delete recording").performClick()
        rule.onNodeWithContentDescription("Stop recording").performClick()
        rule.onNodeWithContentDescription("Send voice message").performClick()
        assertEquals(listOf("delete", "stop", "send"), log)
    }

    @Test fun theLockedBarWaitsWhileTheNoteIsBeingFinished() {
        show(ui(CaptureState.Recording(3.0, stopping = true, phase = VoiceGesture.State.preview)))
        rule.onNodeWithContentDescription("Stop recording").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Send voice message").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Delete recording").assertIsNotEnabled()
    }

    @Test fun thePreviewHasPlayDeleteAndSend() {
        show(ui(CaptureState.Preview(File("/x/note.ogg"), 7.0)))
        rule.onNodeWithText("0:07").assertIsDisplayed()
        rule.onNodeWithContentDescription("Play recording").assertIsDisplayed()
        rule.onNodeWithContentDescription("Delete recording").performClick()
        rule.onNodeWithContentDescription("Send voice message").performClick()
        assertEquals(listOf("delete", "send"), log)
    }

    @Test fun thePreviewPlayButtonTurnsIntoPauseOncePlaying() {
        val engine = object : VoiceEngine {
            var listener: VoiceEngine.Listener? = null
            override fun prepare(file: File, listener: VoiceEngine.Listener) { this.listener = listener }
            override fun start() = Unit
            override fun pause() = Unit
            override val positionMs = 0
            override fun release() = Unit
        }
        show(ui(CaptureState.Preview(File("/x/note.ogg"), 7.0), engines = VoiceEngineFactory { engine }))
        rule.onNodeWithContentDescription("Play recording").performClick()
        rule.runOnIdle { engine.listener!!.onPrepared(7000) }
        rule.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExist() = this.assertDoesNotExist()
}
