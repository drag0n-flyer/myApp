package org.example.app.ui.media

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.MediaState
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.ui.chat.MessageBubble
import org.example.app.ui.theme.AppTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class SoundEngine : VoiceEngine {
    var listener: VoiceEngine.Listener? = null
    var released = false
    override var positionMs = 0
    override fun prepare(file: File, listener: VoiceEngine.Listener) { this.listener = listener }
    override fun start() {}
    override fun pause() {}
    override fun release() { released = true }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class VoiceBubbleTest {
    @get:Rule val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val engines = mutableListOf<SoundEngine>()
    private var downloads = 0
    private val file = File("/plain/m1")

    @After fun stop() = scope.cancel()

    private val ready = MediaState("a1", MediaState.Phase.Local("/blob"))

    private fun voice(media: MediaState?, sender: String = "riya") =
        Message("m1", sender, Instant.now(), MessageContent.Voice(14.0), DeliveryStatus.Read, null, emptyList(), media = media)

    private fun show(message: Message, opener: AttachmentOpener? = AttachmentOpener { object : OpenedMedia { override val file = this@VoiceBubbleTest.file; override fun close() {} } }): VoicePlayer {
        val player = VoicePlayer(scope, opener, { SoundEngine().also { engines += it } }, Dispatchers.Unconfined, tickMs = 100_000)
        rule.setContent {
            AppTheme {
                MessageBubble(
                    message, message.senderId == "me", null, null, { true }, { downloads++ },
                    media = MediaHost(opener, PhotoCache(), fileExists = { true }, voice = player),
                )
            }
        }
        return player
    }

    @Test fun aVoiceMessageShowsItsLengthAndAPlayButton() {
        show(voice(ready))
        rule.onNodeWithContentDescription("Play voice message").assertIsDisplayed()
        rule.onNodeWithText("0:14").assertIsDisplayed()
    }

    @Test fun playTurnsIntoPauseAndTheElapsedTimeReplacesTheLength() {
        val player = show(voice(ready))
        rule.onNodeWithContentDescription("Play voice message").performClick()
        rule.runOnIdle { engines.single().listener!!.onPrepared(14_000) }
        rule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        rule.runOnIdle { engines.single().positionMs = 7_000; player.stop(); }
        rule.onNodeWithContentDescription("Play voice message").assertIsDisplayed()
    }

    @Test fun theElapsedTimeShowsWhilePlaying() {
        val player = show(voice(ready))
        rule.onNodeWithContentDescription("Play voice message").performClick()
        rule.runOnIdle { engines.single().listener!!.onPrepared(14_000) }
        // Pausing reads the position once.
        rule.runOnIdle { engines.single().positionMs = 7_000 }
        rule.onNodeWithContentDescription("Pause").performClick()
        rule.onNodeWithText("0:07").assertIsDisplayed()
        assertTrue(player.state.value.messageId == "m1")
    }

    @Test fun aMessageThatCannotBePlayedSaysSoAndTriesAgain() {
        show(voice(ready), opener = AttachmentOpener { null })
        rule.onNodeWithContentDescription("Play voice message").performClick()
        rule.onNodeWithText("Couldn't download").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        rule.onNodeWithText("Couldn't download").assertIsDisplayed()
        assertEquals(0, downloads)
    }

    @Test fun aVoiceMessageNotYetDownloadedDownloadsOnTap() {
        show(voice(MediaState("a1", MediaState.Phase.Remote)))
        rule.onNodeWithContentDescription("Play voice message").performClick()
        assertEquals(1, downloads)
        assertTrue(engines.isEmpty())
    }

    @Test fun aFailedDownloadOffersToTryAgain() {
        show(voice(MediaState("a1", MediaState.Phase.Failed)))
        rule.onNodeWithText("Couldn't download").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, downloads)
    }

    @Test fun aVoiceMessageISentPlaysWithoutADownload() {
        show(voice(ready, sender = "me"))
        rule.onNodeWithContentDescription("Play voice message").assertIsEnabled().performClick()
        assertEquals(1, engines.size)
        assertEquals(0, downloads)
    }
}
