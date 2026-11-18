package org.example.app.ui.media

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.MediaState
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.ui.chat.MessageBubble
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Records every open and close so a test can see that no plaintext is left open. */
class FakeOpener(private val files: Map<String, File>) : AttachmentOpener {
    var opens = 0
    var closes = 0

    override suspend fun open(messageId: String): OpenedMedia? {
        val f = files[messageId] ?: return null
        opens++
        return object : OpenedMedia {
            override val file = f
            override fun close() { closes++ }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoBubbleTest {
    @get:Rule val rule = createComposeRule()

    private val dir = java.nio.file.Files.createTempDirectory("bubble").toFile()
    private var downloads = 0
    private var opened = mutableListOf<String>()

    private fun png(): File {
        val file = File(dir, "p.png")
        val bmp = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun photo(media: MediaState?, sender: String = "riya") =
        Message("m1", sender, Instant.now(), MessageContent.Photo(240_000), DeliveryStatus.Read, null, emptyList(), media = media)

    private fun show(message: Message, host: MediaHost?, wait: Boolean = false) = rule.setContent {
        AppTheme {
            MessageBubble(
                message = message, isOwn = message.senderId == "me", replyTo = null, replyAuthor = null,
                autoDownloads = { !wait }, onDownload = { downloads++ }, media = host,
            )
        }
    }

    private fun host(opener: AttachmentOpener?, exists: Boolean = true) =
        MediaHost(opener, PhotoCache(), onOpenPhoto = { opened += it }, fileExists = { exists })

    private val loaded = hasContentDescription("Photo") and hasClickAction()

    @Test fun aDownloadedPhotoIsDecodedAndShown() {
        val opener = FakeOpener(mapOf("m1" to png()))
        show(photo(MediaState("a1", MediaState.Phase.Local("/blob"))), host(opener))
        rule.waitUntil(5_000) { rule.onAllNodes(loaded).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(loaded).assertIsDisplayed().performClick()
        assertEquals(listOf("m1"), opened)
        // The plaintext was closed as soon as the picture was decoded.
        assertEquals(1, opener.opens)
        assertEquals(1, opener.closes)
    }

    @Test fun aPhotoSeenAgainComesFromTheCache() {
        val opener = FakeOpener(mapOf("m1" to png()))
        val host = host(opener)
        var visible by mutableStateOf(true)
        val message = photo(MediaState("a1", MediaState.Phase.Local("/blob")))
        rule.setContent {
            AppTheme {
                if (visible) MessageBubble(message, false, null, null, { true }, { downloads++ }, media = host)
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(loaded).fetchSemanticsNodes().isNotEmpty() }
        visible = false
        rule.waitForIdle()
        visible = true
        rule.waitUntil(5_000) { rule.onAllNodes(loaded).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, opener.opens)
    }

    @Test fun aFileThatCannotBeDecodedShowsTheNeutralPlaceholder() {
        val junk = File(dir, "junk").also { it.writeBytes(ByteArray(500) { 3 }) }
        val opener = FakeOpener(mapOf("m1" to junk))
        show(photo(MediaState("a1", MediaState.Phase.Local("/blob"))), host(opener))
        rule.waitUntil(5_000) { opener.closes == 1 }
        rule.onNode(loaded).assertDoesNotExist()
        rule.onNodeWithContentDescription("Photo").assertIsDisplayed()
    }

    @Test fun aPhotoWhoseDownloadIsGoneAsksToTryAgain() {
        show(photo(MediaState("a1", MediaState.Phase.Local("/gone"))), host(FakeOpener(emptyMap()), exists = false))
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Couldn't download")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, downloads)
    }

    @Test fun aFailedDownloadAsksToTryAgain() {
        show(photo(MediaState("a1", MediaState.Phase.Failed)), host(null))
        rule.onNodeWithText("Couldn't download").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, downloads)
    }

    @Test fun aWaitingPhotoOffersDownload() {
        show(photo(MediaState("a1", MediaState.Phase.Remote)), host(null), wait = true)
        rule.onNodeWithContentDescription("Download").performClick()
        assertEquals(1, downloads)
    }

    @Test fun aPhotoWithoutAnOpenerShowsThePlaceholderNotACrash() {
        show(photo(MediaState("a1", MediaState.Phase.Local("/blob"))), host(null))
        rule.onNodeWithContentDescription("Photo").assertIsDisplayed()
    }

    @Test fun myOwnSentPhotoIsShownFromMyCopyWithoutADownload() {
        val opener = FakeOpener(mapOf("m1" to png()))
        show(photo(MediaState("a1", MediaState.Phase.Local("/blob")), sender = "me"), host(opener), wait = true)
        rule.waitUntil(5_000) { rule.onAllNodes(loaded).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(loaded).assertIsDisplayed()
        rule.onNodeWithContentDescription("Download").assertDoesNotExist()
        assertEquals(0, downloads)
    }
}
