package org.example.app.ui.media

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class FileBubbleTest {
    @get:Rule val rule = createComposeRule()

    private var downloads = 0
    private val opened = mutableListOf<Pair<String, String>>()
    private val shared = mutableListOf<Pair<String, String>>()

    private fun file(media: MediaState?, sender: String = "riya") =
        Message("m1", sender, Instant.now(), MessageContent.File("report.pdf", 12_000), DeliveryStatus.Read, null, emptyList(), media = media)

    private fun show(message: Message, exists: Boolean = true, wait: Boolean = false) = rule.setContent {
        val host = MediaHost(
            null, PhotoCache(),
            onOpenFile = { id, name -> opened += id to name },
            onShareFile = { id, name -> shared += id to name },
            fileExists = { exists },
        )
        AppTheme {
            MessageBubble(
                message = message, isOwn = message.senderId == "me", replyTo = null, replyAuthor = null,
                autoDownloads = { !wait }, onDownload = { downloads++ }, media = host,
            )
        }
    }

    private val ready = MediaState("a1", MediaState.Phase.Local("/blob"))

    @Test fun aDownloadedFileShowsItsNameAndSizeAndOpensOnTap() {
        show(file(ready))
        rule.onNode(hasText("report.pdf", substring = true)).assertIsDisplayed()
        rule.onNode(hasText("report.pdf", substring = true) and hasClickAction()).performClick()
        assertEquals(listOf("m1" to "report.pdf"), opened)
    }

    @Test fun aDownloadedFileCanBeShared() {
        show(file(ready))
        rule.onNodeWithContentDescription("Share").performClick()
        assertEquals(listOf("m1" to "report.pdf"), shared)
        assertEquals(emptyList(), opened)
    }

    @Test fun aFileThatIsNotDownloadedOffersDownloadAndNoShare() {
        show(file(MediaState("a1", MediaState.Phase.Remote)), wait = true)
        rule.onNodeWithText("report.pdf", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Share").assertDoesNotExist()
        rule.onNodeWithContentDescription("Download").performClick()
        assertEquals(1, downloads)
    }

    @Test fun aFailedDownloadAsksToTryAgain() {
        show(file(MediaState("a1", MediaState.Phase.Failed)))
        rule.onNodeWithText("Couldn't download").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, downloads)
        rule.onNodeWithContentDescription("Share").assertDoesNotExist()
    }

    @Test fun aFileWhoseDownloadIsGoneAsksToTryAgain() {
        show(file(ready), exists = false)
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Couldn't download")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(emptyList(), opened)
    }

    private fun named(name: String) =
        Message("m1", "riya", Instant.now(), MessageContent.File(name, 12_000), DeliveryStatus.Read, null, emptyList(), media = ready)

    @Test fun aDirectionOverrideNameIsShownAsItWillBeOpened() {
        show(named("report\u202Efdp.html"))
        rule.onNode(hasText("reportfdp.html", substring = true)).assertIsDisplayed()
        rule.onNode(hasText("reportfdp.html", substring = true) and hasClickAction()).performClick()
        assertEquals(listOf("m1" to "reportfdp.html"), opened)
    }

    @Test fun anEndlessNameIsShownShortened() {
        show(named("y".repeat(5_000) + ".pdf"))
        rule.onNode(hasText(".pdf", substring = true)).assertIsDisplayed()
        rule.onNode(hasText("y".repeat(200), substring = true)).assertDoesNotExist()
    }

    @Test fun myOwnSentFileOpensAndSharesWithoutADownload() {
        show(file(ready, sender = "me"), wait = true)
        rule.onNode(hasText("report.pdf", substring = true) and hasClickAction()).performClick()
        assertEquals(listOf("m1" to "report.pdf"), opened)
        rule.onNodeWithContentDescription("Share").performClick()
        assertEquals(listOf("m1" to "report.pdf"), shared)
        rule.onNodeWithContentDescription("Download").assertDoesNotExist()
        assertEquals(0, downloads)
    }
}
