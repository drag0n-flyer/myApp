package org.example.app.ui.chat

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import java.io.File
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import org.example.app.media.Recording
import org.example.app.media.StartResult
import org.example.app.media.StopResult
import org.example.app.ui.media.MediaPlayerEngine
import org.example.app.ui.media.RecorderPort
import org.example.app.ui.media.VoiceCapture
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sample mode: the real [VoiceCapture] and [Composer] wired as the chat screen wires them, on a fake recorder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceFlowTest {
    @get:Rule val rule = createComposeRule()

    private class FakeRecorder(private val file: File) : RecorderPort {
        var cancels = 0
        override fun start() = StartResult.Started
        override fun stop(): StopResult = StopResult.Success(Recording(file, 4.0))
        override fun cancel() { cancels++ }
        override val capturedSeconds = 4.0
    }

    private val file = File.createTempFile("note", ".ogg").also { it.deleteOnExit() }
    private val recorder = FakeRecorder(file)
    private val sent = mutableListOf<File>()

    private fun host() = rule.setContent {
        AppTheme {
            val scope = rememberCoroutineScope()
            val capture = remember { VoiceCapture(scope, recorder, { sent += it }, {}, io = Dispatchers.Unconfined) }
            val state by capture.state.collectAsState()
            val hint by capture.hint.collectAsState()
            Composer(
                enabled = true, text = "", onTextChange = {}, onSend = {}, onAttach = {},
                onRecordVoice = capture::activate,
                voice = VoiceUi(
                    state = state, hint = hint, onPress = capture::press, onDrag = capture::drag, onLift = capture::lift,
                    onStop = capture::stop, onDelete = capture::delete, onSend = capture::send,
                    previewEngines = MediaPlayerEngine.factory,
                ),
            )
        }
    }

    private fun toPreview() {
        rule.onNodeWithContentDescription("Record voice message").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(5_000) { rule.onAllNodesWithDescription("Recording locked") }
        rule.onNodeWithContentDescription("Recording locked").assertIsDisplayed()
        rule.onNodeWithContentDescription("Stop recording").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithDescription("Play recording") }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithDescription(d: String) =
        this.onAllNodesWithContentDescription(d).fetchSemanticsNodes().isNotEmpty()

    @Test fun activateThenStopThenSendAddsOneVoiceMessage() {
        host()
        toPreview()
        rule.onNodeWithContentDescription("Send voice message").performClick()
        rule.waitUntil(5_000) { sent.isNotEmpty() }
        assertEquals(listOf(file), sent)
        rule.onNodeWithContentDescription("Record voice message").assertIsDisplayed()
    }

    @Test fun activateThenStopThenDeleteAddsNone() {
        host()
        toPreview()
        rule.onNodeWithContentDescription("Delete recording").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithDescription("Record voice message") }
        rule.waitUntil(5_000) { !file.exists() }
        assertEquals(emptyList(), sent)
    }

    @Test fun deletingWhileLockedAddsNoneAndCancelsTheRecorder() {
        host()
        rule.onNodeWithContentDescription("Record voice message").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(5_000) { rule.onAllNodesWithDescription("Recording locked") }
        rule.onNodeWithContentDescription("Delete recording").performClick()
        rule.waitUntil(5_000) { recorder.cancels == 1 }
        assertEquals(emptyList(), sent)
    }
}
