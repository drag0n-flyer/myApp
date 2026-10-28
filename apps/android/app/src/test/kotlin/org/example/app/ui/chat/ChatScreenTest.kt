package org.example.app.ui.chat

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.ChatEncryption
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.ui.allLabels
import org.example.app.ui.msg
import org.example.app.ui.storeWith
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class ChatScreenTest {
    @get:Rule val rule = createComposeRule()

    private var backs = 0
    private var infos = 0
    private val calls = mutableListOf<Boolean>()

    private fun launch(store: ChatStore, network: NetworkType = NetworkType.Wifi) {
        val vm = ChatViewModel(store, "chat-riya", network) { AutoDownloadSettings() }
        rule.setContent {
            AppTheme { ChatScreen(vm, myId = "me", onBack = { backs++ }, onOpenInfo = { infos++ }, onCall = { calls += it }) }
        }
    }

    private val photoAndVoice = listOf(
        msg("1", "riya", MessageContent.Voice(14.0)),
        msg("2", "riya", MessageContent.Photo(240_000)),
    )

    @Test
    fun photoOnMobileDataIsBlurredWithSizeAndDownloadButton() {
        launch(storeWith(photoAndVoice), NetworkType.Mobile)
        rule.onNodeWithText("Photo · 240 KB").assertIsDisplayed()
        rule.onNodeWithContentDescription("Download").assertIsDisplayed()
    }

    @Test
    fun voiceMessageHasNoDownloadButton() {
        launch(storeWith(listOf(photoAndVoice[0])), NetworkType.Mobile)
        rule.onNodeWithContentDescription("Play voice message").assertIsDisplayed()
        rule.onNodeWithContentDescription("Download").assertDoesNotExist()
    }

    @Test
    fun photoOnWifiShowsNoDownloadButton() {
        launch(storeWith(photoAndVoice), NetworkType.Wifi)
        rule.onNodeWithText("Photo · 240 KB").assertIsDisplayed()
        rule.onNodeWithContentDescription("Download").assertDoesNotExist()
    }

    private class RecordingStore(inner: SampleChatStore) : ChatStore by inner {
        val downloads = mutableListOf<String>()
        override fun download(messageId: String, chatId: String) { downloads += messageId }
    }

    @Test
    fun photoOnWifiIsDownloadedOncePerMessage() {
        val store = RecordingStore(storeWith(photoAndVoice))
        launch(store, NetworkType.Wifi)
        rule.waitForIdle()
        rule.onNodeWithText("Photo · 240 KB").assertIsDisplayed()
        assertEquals(listOf("2"), store.downloads)
    }

    @Test
    fun scrollingAPhotoAwayAndBackDoesNotRequestItAgain() {
        val chat = listOf(msg("photo", "riya", MessageContent.Photo(240_000))) +
            (0 until 300).map { msg("m$it", "riya", MessageContent.Text("message $it")) }
        val store = RecordingStore(storeWith(chat))
        launch(store, NetworkType.Wifi)
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToIndex(300)
        rule.waitForIdle()
        assertEquals(listOf("photo"), store.downloads)
        rule.onNode(hasScrollAction()).performScrollToIndex(0)
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToIndex(300)
        rule.waitForIdle()
        assertEquals(listOf("photo"), store.downloads)
    }

    @Test
    fun photoOnMobileDataIsNotDownloadedByItself() {
        val store = RecordingStore(storeWith(photoAndVoice))
        launch(store, NetworkType.Mobile)
        rule.waitForIdle()
        assertEquals(emptyList(), store.downloads)
    }

    private fun longChat(): SampleChatStore =
        storeWith((0 until 300).map { msg("m$it", "riya", MessageContent.Text("message $it")) })

    @Test
    fun readingOlderMessagesKeepsPositionWhenOneArrives() {
        val store = longChat()
        launch(store)
        rule.onNode(hasScrollAction()).performScrollToIndex(150)
        rule.waitForIdle()
        val before = rule.allLabels().filter { it.startsWith("message ") }.toSet()
        rule.runOnIdle { store.receive(MessageContent.Text("fresh"), store.contact("riya")!!) }
        rule.waitForIdle()
        rule.onNodeWithText("fresh").assertDoesNotExist()
        assertEquals(before, rule.allLabels().filter { it.startsWith("message ") }.toSet())
    }

    @Test
    fun atTheNewestMessageAnArrivingMessageScrollsIntoView() {
        val store = longChat()
        launch(store)
        rule.runOnIdle { store.receive(MessageContent.Text("fresh"), store.contact("riya")!!) }
        rule.waitForIdle()
        rule.onNodeWithText("fresh").assertIsDisplayed()
    }

    @Test
    fun sendingYourOwnMessageScrollsToItEvenWhenReadingOlderOnes() {
        val store = longChat()
        launch(store)
        rule.onNode(hasScrollAction()).performScrollToIndex(150)
        rule.waitForIdle()
        rule.runOnIdle { store.send(MessageContent.Text("mine"), "chat-riya") }
        rule.waitForIdle()
        rule.onNodeWithText("mine").assertIsDisplayed()
    }

    @Test
    fun composerHasNoEmojiButton() {
        launch(storeWith(photoAndVoice))
        rule.onNodeWithContentDescription("Emoji").assertDoesNotExist()
    }

    @Test
    fun micTurnsIntoSendWhenTextIsTyped() {
        val store = storeWith(listOf(msg("1", "riya", MessageContent.Text("hi"))))
        launch(store)
        rule.onNodeWithContentDescription("Record voice message").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send").assertDoesNotExist()

        rule.onNodeWithText("Message").performTextInput("On my way")
        rule.onNodeWithContentDescription("Send").assertIsDisplayed()
        rule.onNodeWithContentDescription("Record voice message").assertDoesNotExist()

        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitForIdle()
        assertEquals(MessageContent.Text("On my way"), store.chat("chat-riya")!!.messages.last().content)
        rule.onNodeWithContentDescription("Record voice message").assertIsDisplayed()
    }

    @Test
    fun typedTextIsCutAtTheGraphemeLimit() {
        val store = storeWith(listOf(msg("1", "riya", MessageContent.Text("hi"))))
        launch(store)
        rule.onNodeWithText("Message").performTextInput("a".repeat(5000))
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitForIdle()
        val sent = store.chat("chat-riya")!!.messages.last().content as MessageContent.Text
        assertEquals(4096, sent.text.length)
    }

    @Test
    fun headerOpensInfoAndStartsCalls() {
        launch(storeWith(photoAndVoice))
        rule.onNodeWithText("Riya").performClick()
        rule.onNodeWithContentDescription("Audio call").performClick()
        rule.onNodeWithContentDescription("Video call").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, infos)
        assertEquals(listOf(false, true), calls)
        assertEquals(1, backs)
    }

    @Test
    fun unsupportedMessageNamesTheApp() {
        launch(storeWith(listOf(msg("1", "riya", MessageContent.Unsupported))))
        rule.onNodeWithText("Update [App] to see this message").assertIsDisplayed()
    }

    @Test
    fun pausedChatDisablesSending() {
        launch(storeWith(listOf(msg("1", "riya", MessageContent.Text("hi"))), modeMismatch = true))
        rule.onNodeWithText("Sending is paused. Open chat info for details.").assertIsDisplayed()
    }

    private fun noEncryptionWords(enc: ChatEncryption) {
        val messages = listOf(msg("1", "riya", MessageContent.Text("hello")), msg("2", "me", MessageContent.Text("hi")))
        launch(storeWith(messages, enc))
        val offenders = rule.allLabels().filter { it.contains("encrypt", ignoreCase = true) }
        assertTrue(offenders.isEmpty(), "$enc: $offenders")
    }

    @Test
    fun noEncryptionWordsOnAnEndToEndChat() = noEncryptionWords(ChatEncryption.EndToEnd)

    @Test
    fun noEncryptionWordsOnAServerReadableChat() = noEncryptionWords(ChatEncryption.ServerReadable)

    @Test
    fun everyClickableHasALabel() {
        launch(storeWith(photoAndVoice), NetworkType.Mobile)
        val unlabeled = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().isEmpty() &&
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().isEmpty()
        }
        assertTrue(unlabeled.isEmpty(), "unlabelled clickable: $unlabeled")
    }
}
