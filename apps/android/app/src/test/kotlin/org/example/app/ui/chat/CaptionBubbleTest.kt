package org.example.app.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import java.time.Instant
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.MediaState
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class CaptionBubbleTest {
    @get:Rule val rule = createComposeRule()

    private fun show(content: MessageContent) = rule.setContent {
        val message = Message(
            "m1", "riya", Instant.now(), content, DeliveryStatus.Read, null, emptyList(),
            media = MediaState("a1", MediaState.Phase.Remote),
        )
        AppTheme {
            MessageBubble(message, isOwn = false, replyTo = null, replyAuthor = null, autoDownloads = { false }, onDownload = {})
        }
    }

    @Test fun aPhotoShowsItsCaptionBelowIt() {
        show(MessageContent.Photo(2_000, "Sunset at the harbour"))
        rule.onNodeWithText("Sunset at the harbour").assertIsDisplayed()
    }

    @Test fun aFileShowsItsCaptionAndTheCaptionIsFormattedLikeText() {
        show(MessageContent.File("report.pdf", 12_000, "application/pdf", "the *final* one"))
        // The markers are not shown: the caption is formatted like message text.
        rule.onNodeWithText("the final one").assertIsDisplayed()
    }

    @Test fun aVideoShowsItsCaption() {
        show(MessageContent.File("clip.mp4", 12_000, "video/mp4", "the trip"))
        rule.onNodeWithText("the trip").assertIsDisplayed()
    }

    @Test fun noCaptionShowsNoExtraText() {
        show(MessageContent.Photo(2_000))
        rule.onNodeWithText("Sunset at the harbour").assertDoesNotExist()
    }
}
