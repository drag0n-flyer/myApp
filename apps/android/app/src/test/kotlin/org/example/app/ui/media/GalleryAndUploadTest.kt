package org.example.app.ui.media

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.ui.chat.ChatScreen
import org.example.app.ui.chat.ChatViewModel
import org.example.app.ui.chatinfo.ChatInfoScreen
import org.example.app.ui.msg
import org.example.app.ui.storeWith
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sample mode: the gallery from chat info, empty states, and Cancel on an upload that is on its way. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class GalleryAndUploadTest {
    @get:Rule val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun waitForText(text: String) = rule.waitUntil(5_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun galleryShowsTheSamplePhotoAsADownloadTileAndTheSampleFileOnTheFilesTab() {
        val store = SampleChatStore()
        rule.setContent {
            AppTheme { GalleryScreen(store, "chat-riya", opener = null, onBack = {}) }
        }
        rule.onNodeWithText("Media and files").assertIsDisplayed()
        rule.onNodeWithText("Media").assertIsDisplayed()
        rule.onNodeWithText("Files").assertIsDisplayed()
        // The sample photo is not downloaded: its square offers Download.
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Download")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun theFilesTabListsTheSampleFileWithItsSize() {
        val store = SampleChatStore()
        val chatWithFile = store.chats.value.first { chat -> chat.messages.any { it.content is MessageContent.File } }
        rule.setContent {
            AppTheme { GalleryScreen(store, chatWithFile.id, opener = null, onBack = {}) }
        }
        rule.onNodeWithText("Files").performClick()
        waitForText("Report.pdf")
    }

    @Test fun emptyTabsSayNoMediaYetAndNoFilesYet() {
        val store = storeWith(listOf(msg("1", "riya", MessageContent.Text("hello"))))
        rule.setContent {
            AppTheme { GalleryScreen(store, "chat-riya", opener = null, onBack = {}) }
        }
        waitForText("No media yet")
        rule.onNodeWithText("Files").performClick()
        waitForText("No files yet")
    }

    @Test fun chatInfoOpensTheGallery() {
        val store = SampleChatStore()
        var opened = 0
        rule.setContent {
            AppTheme {
                ChatInfoScreen(store, "chat-riya", onBack = {}, onCall = null, onSearch = {}, onBlocked = {}, onOpenGallery = { opened++ })
            }
        }
        rule.onNodeWithText("Media and files").performClick()
        assertEquals(1, opened)
    }

    @Test fun cancelOnAnUploadRemovesTheRowAndAnotherMessageStays() {
        val store = storeWith(
            listOf(
                msg("1", "riya", MessageContent.Text("hello")),
                msg("2", "me", MessageContent.Photo(240_000), DeliveryStatus.Sending),
            ),
        )
        val vm = ChatViewModel(store, "chat-riya", NetworkType.Wifi) { AutoDownloadSettings() }
        rule.setContent {
            AppTheme { ChatScreen(vm, myId = "me", onBack = {}, onOpenInfo = {}, onCall = null) }
        }
        rule.onNodeWithContentDescription("Cancel upload").assertIsDisplayed()
        rule.onNodeWithContentDescription("Cancel upload").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("Cancel upload").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("1"), store.chat("chat-riya")!!.messages.map { it.id })
    }
}
