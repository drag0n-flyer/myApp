package org.example.app.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.LinkPreviewCard
import org.example.app.core.chats.LinkPreviewMaker
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.settings.AutoDownloadSettings
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
class LinkPreviewUiTest {
    @get:Rule val rule = createComposeRule()

    private val card = LinkPreviewCard("https://example.org/harbour", "Harbour guide", "Where to park and eat.", null)

    private class CountingMaker(val answer: LinkPreviewCard?) : LinkPreviewMaker {
        val urls = mutableListOf<String>()
        override suspend fun make(url: String): LinkPreviewCard? { urls += url; return answer }
    }

    private class RecordingStore(inner: SampleChatStore) : ChatStore by inner {
        val sent = mutableListOf<MessageContent.Text>()
        override fun send(content: MessageContent, chatId: String, replyTo: String?) { (content as? MessageContent.Text)?.let { sent += it } }
    }

    private fun launch(store: ChatStore, support: LinkPreviewSupport?, opened: MutableList<String> = mutableListOf()) {
        val vm = ChatViewModel(store, "chat-riya", NetworkType.Wifi) { AutoDownloadSettings() }
        rule.setContent {
            val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
            CompositionLocalProvider(LocalLinkPreviews provides support, LocalUriHandler provides handler) {
                AppTheme { ChatScreen(vm, myId = "me", onBack = {}, onOpenInfo = {}, onCall = null) }
            }
        }
    }

    private fun support(maker: LinkPreviewMaker, on: Boolean) = LinkPreviewSupport(maker, MutableStateFlow(on))

    private fun typeLink() {
        rule.onNodeWithText("Message").performTextInput("look https://example.org/harbour")
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
    }

    // Receiving: what the message carries is shown, nothing is fetched

    @Test fun aReceivedPreviewIsShownFromTheMessageAndRenderingFetchesNothing() {
        val maker = CountingMaker(card)
        val opened = mutableListOf<String>()
        launch(storeWith(listOf(msg("1", "riya", MessageContent.Text("see https://example.org/harbour", card)))), support(maker, on = true), opened)
        rule.onNodeWithText("Harbour guide").assertIsDisplayed()
        rule.onNodeWithText("Where to park and eat.").assertIsDisplayed()
        rule.onNodeWithText("example.org").assertIsDisplayed()
        rule.waitForIdle()
        assertEquals(emptyList(), maker.urls, "a receiver never asks for a preview")
        rule.onNodeWithText("Harbour guide").performClick()
        assertEquals(listOf("https://example.org/harbour"), opened, "only the tap opens the link")
    }

    @Test fun aReceivedPreviewShowsEvenWhenThePrivacySwitchIsOffAndNoMakerExists() {
        launch(storeWith(listOf(msg("1", "riya", MessageContent.Text("see https://example.org/harbour", card)))), null)
        rule.onNodeWithText("Harbour guide").assertIsDisplayed()
    }

    @Test fun aTextWithoutAPreviewShowsNoCard() {
        launch(storeWith(listOf(msg("1", "riya", MessageContent.Text("see https://example.org/harbour")))), null)
        rule.onNodeWithText("Harbour guide").assertDoesNotExist()
    }

    // Composing

    @Test fun withTheSwitchOffATypedLinkNeverContactsAnything() {
        val maker = CountingMaker(card)
        launch(storeWith(emptyList()), support(maker, on = false))
        typeLink()
        assertEquals(emptyList(), maker.urls)
        rule.onNodeWithContentDescription("Remove preview").assertDoesNotExist()
    }

    @Test fun withTheSwitchOnTheComposerShowsThePreviewAndItGoesWithTheMessage() {
        val maker = CountingMaker(card)
        val store = RecordingStore(storeWith(emptyList()))
        launch(store, support(maker, on = true))
        typeLink()
        assertEquals(listOf("https://example.org/harbour"), maker.urls)
        rule.onNodeWithText("Harbour guide").assertIsDisplayed()
        rule.onNodeWithContentDescription("Remove preview").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitForIdle()
        assertEquals(card, store.sent.single().preview)
        assertEquals("look https://example.org/harbour", store.sent.single().text)
        rule.onNodeWithContentDescription("Remove preview").assertDoesNotExist()
    }

    @Test fun removingThePreviewSendsTheTextWithoutItAndItStaysRemovedForThatDraft() {
        val maker = CountingMaker(card)
        val store = RecordingStore(storeWith(emptyList()))
        launch(store, support(maker, on = true))
        typeLink()
        rule.onNodeWithContentDescription("Remove preview").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Remove preview").assertDoesNotExist()
        // More typing in the same draft does not bring it back or ask again.
        rule.onNodeWithText("look https://example.org/harbour").performTextInput(" please")
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertEquals(1, maker.urls.size)
        rule.onNodeWithContentDescription("Remove preview").assertDoesNotExist()
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitForIdle()
        assertNull(store.sent.single().preview)
    }

    private fun typedTextMakesNoRequest(text: String) {
        val maker = CountingMaker(card)
        launch(storeWith(emptyList()), support(maker, on = true))
        rule.onNodeWithText("Message").performTextInput(text)
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertEquals(emptyList(), maker.urls, text)
    }

    @Test fun anHttpLinkGetsNoLookup() = typedTextMakesNoRequest("see http://example.org/a")
    @Test fun aLinkInCodeGetsNoLookup() = typedTextMakesNoRequest("see `https://example.org/a`")
    @Test fun textWithoutALinkGetsNoLookup() = typedTextMakesNoRequest("no link at all")
    @Test fun aBareWwwNameGetsNoLookup() = typedTextMakesNoRequest("www.example.org")

    @Test fun aMakerThatFindsNothingLeavesNoCardAndTheTextStillSends() {
        val maker = CountingMaker(null)
        val store = RecordingStore(storeWith(emptyList()))
        launch(store, support(maker, on = true))
        typeLink()
        rule.onNodeWithContentDescription("Remove preview").assertDoesNotExist()
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitForIdle()
        assertNull(store.sent.single().preview)
        assertTrue(store.sent.single().text.contains("example.org"))
    }

    @Test fun theSampleChatHasAMessageWithACannedPreview() {
        val vm = ChatViewModel(SampleChatStore(), "chat-kiran", NetworkType.Wifi) { AutoDownloadSettings() }
        rule.setContent { AppTheme { ChatScreen(vm, myId = "me", onBack = {}, onOpenInfo = {}, onCall = null) } }
        rule.onNodeWithText("Harbour guide").assertIsDisplayed()
    }

    @Test fun r8AFetchInFlightIsCancelledWhenTheChatScreenLeavesComposition() {
        val started = java.util.concurrent.CountDownLatch(1)
        var cancelled = false
        val maker = LinkPreviewMaker {
            started.countDown()
            try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true }
        }
        val shown = androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalLinkPreviews provides support(maker, on = true)) {
                if (shown.value) rememberComposerPreview("see https://example.org/harbour", editing = false)
            }
        }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // The screen goes away (back, or the app-lock cover replaces the navigation host).
        shown.value = false
        rule.waitForIdle()
        rule.waitUntil(5_000) { cancelled }
        assertTrue(cancelled)
    }
}
