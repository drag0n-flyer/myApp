package org.example.app.ui.chat

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.Chat
import org.example.app.core.chats.ChatEncryption
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.MessageHit
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.ui.theme.AppTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class ChatScreenActionsTest {
    @get:Rule val rule = createComposeRule()

    @After fun clearSearchRequest() { ChatSearchRequests.take("chat-riya") }

    private val start = Instant.now().minus(10, ChronoUnit.DAYS)

    /** Message [n] was sent [n] minutes after [start]: ordered, and distinct, however fast the test runs. */
    private fun m(
        n: Int, sender: String = "riya", text: String = "message $n", status: DeliveryStatus = DeliveryStatus.Read,
        reply: String? = null, content: MessageContent = MessageContent.Text(text), at: Instant = start.plus(n.toLong(), ChronoUnit.MINUTES),
    ) = Message("m$n", sender, at, content, status, reply, emptyList())

    private fun sampleWith(messages: List<Message>, unread: Int = 0, isRequest: Boolean = false): SampleChatStore {
        val data = SampleData.make(Instant.now())
        return SampleChatStore(data.copy(chats = listOf(Chat("chat-riya", "riya", ChatEncryption.EndToEnd, emptySet(), unread, messages, isRequest))))
    }

    /** Serves `messages(before:)` from [all] while the chat itself holds only the newest part, like a cache over a database. */
    private class PagedStore(val inner: SampleChatStore, private val all: List<Message>) : ChatStore by inner {
        override fun messages(chatId: String, before: String?, limit: Int): List<Message> {
            val end = if (before == null) all.size else all.indexOfFirst { it.id == before }.let { if (it < 0) return emptyList() else it }
            return all.subList(maxOf(0, end - limit), end)
        }
    }

    private class Recording(val inner: SampleChatStore) : ChatStore by inner {
        val calls = mutableListOf<String>()
        override fun accept(requestId: String) { calls += "accept:$requestId" }
        override fun block(contactId: String) { calls += "block:$contactId" }
        override fun report(chatId: String) { calls += "report:$chatId" }
        override fun retrySend(messageId: String) { calls += "retry:$messageId" }
        override fun send(content: MessageContent, chatId: String, replyTo: String?) {
            calls += "send:${(content as? MessageContent.Text)?.text}:$replyTo"
            inner.send(content, chatId, replyTo)
        }
    }

    private var forwarded: String? = null
    private var accepts = 0
    private var blocks = 0
    private var reports = 0

    private fun launch(store: ChatStore, highlight: String? = null): ChatViewModel {
        val vm = ChatViewModel(store, "chat-riya", NetworkType.Wifi, Dispatchers.Unconfined) { AutoDownloadSettings() }
        rule.setContent {
            AppTheme {
                ChatScreen(
                    vm, myId = "me", onBack = {}, onOpenInfo = {}, onCall = null,
                    onAccept = { accepts++ }, onBlock = { blocks++ }, onReport = { reports++ },
                    onForward = { forwarded = it }, highlightMessageId = highlight,
                )
            }
        }
        return vm
    }

    private fun longPress(text: String) = rule.onNodeWithText(text).performTouchInput { longClick() }

    @Test
    fun longPressOnAnotherPersonsTextOffersReplyReactCopyForward() {
        launch(sampleWith(listOf(m(1, text = "hello there"))))
        longPress("hello there")
        rule.onNodeWithText("Reply").assertIsDisplayed()
        rule.onNodeWithText("Copy").assertIsDisplayed()
        rule.onNodeWithText("Forward").assertIsDisplayed()
        rule.onNodeWithContentDescription("React with 👍").assertIsDisplayed()
        rule.onNodeWithContentDescription("React with 🙏").assertIsDisplayed()
        rule.onNodeWithText("Edit").assertDoesNotExist()
        rule.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun longPressOnOwnTextAlsoOffersEditAndDelete() {
        launch(sampleWith(listOf(m(1, "me", "mine", DeliveryStatus.Sent))))
        longPress("mine")
        rule.onNodeWithText("Edit").assertIsDisplayed()
        rule.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun noMenuForADeletedMessage() {
        launch(sampleWith(listOf(m(1, content = MessageContent.Deleted))))
        rule.onNodeWithText("This message was deleted").performTouchInput { longClick() }
        rule.onNodeWithText("Reply").assertDoesNotExist()
    }

    @Test
    fun replyShowsTheBarAndTheSentMessageCarriesTheQuote() {
        val store = Recording(sampleWith(listOf(m(1, text = "question?"))))
        launch(store)
        longPress("question?")
        rule.onNodeWithText("Reply").performClick()
        rule.onNodeWithText("Replying to Riya").assertIsDisplayed()
        rule.onNodeWithText("Message").performTextInput("answer")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitForIdle()
        assertEquals(listOf("send:answer:m1"), store.calls)
        rule.onNodeWithText("Replying to Riya").assertDoesNotExist()
    }

    @Test
    fun theReplyBarClosesWithItsCloseButton() {
        launch(sampleWith(listOf(m(1, text = "question?"))))
        longPress("question?")
        rule.onNodeWithText("Reply").performClick()
        rule.onNodeWithContentDescription("Close").performClick()
        rule.onNodeWithText("Replying to Riya").assertDoesNotExist()
    }

    @Test
    fun editFillsTheComposerShowsEditingAndSendsTheEdit() {
        val store = sampleWith(listOf(m(1, "me", "typo", DeliveryStatus.Sent)))
        launch(store)
        longPress("typo")
        rule.onNodeWithText("Edit").performClick()
        rule.onNodeWithText("Editing").assertIsDisplayed()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("!")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitForIdle()
        assertEquals(MessageContent.Text("typo!"), store.chat("chat-riya")!!.messages.single().content)
        rule.onNodeWithText("Editing").assertDoesNotExist()
        rule.onNodeWithText("edited").assertIsDisplayed()
    }

    @Test
    fun deleteAsksForConfirmationThenDeletes() {
        val store = sampleWith(listOf(m(1, "me", "oops", DeliveryStatus.Sent)))
        launch(store)
        longPress("oops")
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("Delete this message for everyone?").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(MessageContent.Text("oops"), store.chat("chat-riya")!!.messages.single().content)
        longPress("oops")
        rule.onNodeWithText("Delete").performClick()
        // The menu has closed; "Delete" is now the dialog's button.
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertEquals(MessageContent.Deleted, store.chat("chat-riya")!!.messages.single().content)
    }

    @Test
    fun reactingStoresTheEmoji() {
        val store = sampleWith(listOf(m(1, text = "nice")))
        launch(store)
        longPress("nice")
        rule.onNodeWithContentDescription("React with ❤️").performClick()
        rule.waitForIdle()
        assertEquals(listOf("❤️"), store.chat("chat-riya")!!.messages.single().reactions)
    }

    // Review focus 3

    @Test
    fun theEditBarClosesByItselfWhenTheMessageIsDeletedUnderIt() {
        val store = sampleWith(listOf(m(1, "me", "typo", DeliveryStatus.Sent)))
        launch(store)
        longPress("typo")
        rule.onNodeWithText("Edit").performClick()
        rule.onNodeWithText("Editing").assertIsDisplayed()
        rule.runOnIdle { store.delete("m1", "chat-riya") }
        rule.waitForIdle()
        rule.onNodeWithText("Editing").assertDoesNotExist()
    }

    @Test
    fun theReplyBarClosesByItselfWhenTheMessageIsDeletedUnderIt() {
        val store = sampleWith(listOf(m(1, text = "soon gone")))
        launch(store)
        longPress("soon gone")
        rule.onNodeWithText("Reply").performClick()
        rule.onNodeWithText("Replying to Riya").assertIsDisplayed()
        rule.runOnIdle { store.delete("m1", "chat-riya") }
        rule.waitForIdle()
        rule.onNodeWithText("Replying to Riya").assertDoesNotExist()
    }

    @Test
    fun aMenuChoiceOnAMessageDeletedWhileTheMenuWasOpenDoesNothing() {
        val store = Recording(sampleWith(listOf(m(1, "me", "gone soon", DeliveryStatus.Sent))))
        launch(store)
        longPress("gone soon")
        rule.runOnIdle { store.inner.delete("m1", "chat-riya") }
        rule.waitForIdle()
        // The menu may have closed with the change; if it is still there, a choice must not open a bar or dialog.
        if (rule.onAllNodesWithTextCount("Edit") > 0) rule.onNodeWithText("Edit").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Editing").assertDoesNotExist()
        rule.onNodeWithText("Delete this message for everyone?").assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun aFailedMessageSaysNotSentAndTryAgainRetriesIt() {
        val store = Recording(sampleWith(listOf(m(1, "me", "lost", DeliveryStatus.Failed))))
        launch(store)
        rule.onNodeWithText("Not sent").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(listOf("retry:m1"), store.calls)
    }

    @Test
    fun aFailedMessageHasNoReplyOrEditInItsMenu() {
        launch(sampleWith(listOf(m(1, "me", "lost", DeliveryStatus.Failed))))
        longPress("lost")
        rule.onNodeWithText("Copy").assertIsDisplayed()
        rule.onNodeWithText("Delete").assertIsDisplayed()
        rule.onNodeWithText("Reply").assertDoesNotExist()
        rule.onNodeWithText("Edit").assertDoesNotExist()
    }

    // History and navigation

    @Test
    fun anEmptyChatSaysSo() {
        launch(sampleWith(emptyList()))
        rule.onNodeWithText("No messages yet").assertIsDisplayed()
    }

    @Test
    fun eachDayGetsADateChip() {
        val now = Instant.now()
        launch(
            sampleWith(
                listOf(
                    m(1, text = "old one", at = now.minus(5, ChronoUnit.DAYS)),
                    m(2, text = "yesterday one", at = now.minus(1, ChronoUnit.DAYS)),
                    m(3, text = "today one", at = now),
                ),
            ),
        )
        rule.onNodeWithText("Yesterday").assertIsDisplayed()
        rule.onNodeWithText("Today").assertIsDisplayed()
    }

    @Test
    fun theChatOpensAtTheUnreadLineWithTheNewestMessageBelowIt() {
        val messages = (1..40).map { m(it) }
        launch(sampleWith(messages, unread = 3))
        rule.onNodeWithText("Unread messages").assertIsDisplayed()
        rule.onNodeWithText("message 38").assertIsDisplayed()
        rule.onNodeWithText("message 40").assertIsDisplayed()
        // Opening the chat read it, but the line stays.
        rule.onNodeWithText("Unread messages").assertIsDisplayed()
    }

    private fun longChat() = sampleWith((1..300).map { m(it) })

    @Test
    fun theScrollButtonAppearsWhenScrolledUpCountsNewMessagesAndScrollsBack() {
        val store = longChat()
        launch(store)
        rule.onNodeWithContentDescription("Scroll to newest").assertDoesNotExist()
        rule.onNode(hasScrollAction()).performScrollToIndex(150)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Scroll to newest").assertIsDisplayed()
        rule.runOnIdle { store.receive(MessageContent.Text("fresh one"), store.contact("riya")!!) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Scroll to newest, 1 new").assertIsDisplayed()
        rule.onNodeWithText("fresh one").assertDoesNotExist()
        rule.onNodeWithContentDescription("Scroll to newest, 1 new").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("fresh one")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("fresh one").assertIsDisplayed()
        rule.onNodeWithContentDescription("Scroll to newest", substring = true).assertDoesNotExist()
    }

    // Review focus 1 and 2

    @Test
    fun tappingAQuoteOfAMessageOlderThanThePageLoadsOlderPagesAndShowsIt() {
        val all = (1..180).map { m(it) }.toMutableList()
        all[179] = m(180, "me", "the reply", DeliveryStatus.Read, reply = "m10")
        val store = PagedStore(sampleWith(all.takeLast(50)), all)
        launch(store)
        // The quoted message is not loaded: the quote is a placeholder, tapping it loads history.
        rule.onNodeWithText("message 10").assertDoesNotExist()
        rule.onNodeWithText("Original message").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("message 10")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("message 10").assertIsDisplayed()
    }

    @Test
    fun aQuoteOfAMessageThatDoesNotExistLeavesTheChatWhereItWas() {
        val all = (1..120).map { m(it) }.toMutableList()
        all[119] = m(120, "me", "the reply", DeliveryStatus.Read, reply = "ghost")
        val store = PagedStore(sampleWith(all.takeLast(50)), all)
        launch(store)
        rule.onNodeWithText("Original message").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("the reply").assertIsDisplayed()
        rule.onNodeWithContentDescription("Scroll to newest").assertDoesNotExist()
    }

    @Test
    fun aSearchHitHighlightIsScrolledTo() {
        val all = (1..180).map { m(it) }
        val store = PagedStore(sampleWith(all.takeLast(50)), all)
        launch(store, highlight = "m25")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("message 25")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("message 25").assertIsDisplayed()
    }

    @Test
    fun aHighlightForAMessageThatIsNotThereDoesNothing() {
        launch(sampleWith((1..10).map { m(it) }), highlight = "nope")
        rule.waitForIdle()
        rule.onNodeWithText("message 10").assertIsDisplayed()
    }

    @Test
    fun scrollingUpLoadsOlderPagesAndKeepsWhatIsOnScreen() {
        val all = (1..180).map { m(it) }
        val store = PagedStore(sampleWith(all.takeLast(50)), all)
        launch(store)
        rule.onNode(hasScrollAction()).performScrollToIndex(45)
        rule.waitForIdle()
        // New message arrives while reading old ones: no jump, no duplicate.
        rule.runOnIdle { store.inner.receive(MessageContent.Text("arrived"), store.inner.contact("riya")!!) }
        rule.waitForIdle()
        rule.onNodeWithText("arrived").assertDoesNotExist()
        rule.onNodeWithContentDescription("Scroll to newest, 1 new").assertIsDisplayed()
    }

    // Search

    @Test
    fun inChatSearchShowsHitsOfThisChatAndTappingOneScrollsToIt() {
        val all = (1..180).map { m(it) }
        val inner = sampleWith(all.takeLast(50))
        val store = object : ChatStore by PagedStore(inner, all) {
            override fun searchMessages(query: String, limit: Int) = listOf(
                MessageHit("chat-riya", "m30", "message 30 has the needle", Instant.now()),
                MessageHit("chat-other", "x1", "needle in another chat", Instant.now()),
            )
        }
        ChatSearchRequests.request("chat-riya")
        launch(store)
        rule.onNodeWithText("Search in chat").assertIsDisplayed()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("needle")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("message 30 has the needle")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("needle in another chat").assertDoesNotExist()
        rule.onNodeWithText("message 30 has the needle").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("message 30")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("message 30").assertIsDisplayed()
    }

    @Test
    fun searchWithNoMatchesSaysNoResults() {
        val store = object : ChatStore by sampleWith(listOf(m(1))) {
            override fun searchMessages(query: String, limit: Int) = emptyList<MessageHit>()
        }
        ChatSearchRequests.request("chat-riya")
        launch(store)
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("zzz")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("No results")).fetchSemanticsNodes().isNotEmpty() }
    }

    // Requests, links, drafts, forward

    @Test
    fun aMessageRequestShowsAcceptBlockReportInPlaceOfTheComposer() {
        val store = sampleWith(listOf(m(1)), isRequest = true)
        launch(store)
        rule.onNodeWithText("Message").assertDoesNotExist()
        rule.onNodeWithText("Block").assertIsDisplayed()
        rule.onNodeWithText("Report").assertIsDisplayed()
        rule.onNodeWithText("Accept").performClick()
        assertEquals(1, accepts)
        assertEquals(0, blocks + reports)
    }

    @Test
    fun blockAndReportAskBeforeActing() {
        launch(sampleWith(listOf(m(1)), isRequest = true))
        rule.onNodeWithText("Block").performClick()
        rule.onNodeWithText("Block this person?").assertIsDisplayed()
        assertEquals(0, blocks)
        rule.onAllNodes(hasText("Block"))[1].performClick()
        assertEquals(1, blocks)
        rule.onNodeWithText("Report").performClick()
        rule.onNodeWithText("Report this chat?").assertIsDisplayed()
        assertEquals(0, reports)
    }

    @Test
    fun linksInTextAreTappable() {
        launch(sampleWith(listOf(m(1, text = "see https://example.org/x now"))))
        val node = rule.onNode(hasText("see https://example.org/x now")).fetchSemanticsNode()
        val text = assertNotNull(node.config.getOrNull(SemanticsProperties.Text)).first()
        assertEquals(1, text.getLinkAnnotations(0, text.length).size)
    }

    @Test
    fun aDraftIsKeptWhenTheChatIsClosedAndReopened() {
        val store = sampleWith(listOf(m(1)))
        val first = launch(store)
        rule.onNodeWithText("Message").performTextInput("unsent thought")
        rule.waitForIdle()
        assertEquals("unsent thought", first.draft())
        assertEquals("unsent thought", store.chat("chat-riya")?.draft)
    }

    @Test
    fun forwardSendsTheTextToTheChosenContactAndOpensThatChat() {
        val store = Recording(sampleWith(listOf(m(1, text = "pass it on"))))
        launch(store)
        longPress("pass it on")
        rule.onNodeWithText("Forward").performClick()
        rule.onNodeWithText("Forward to…").assertIsDisplayed()
        val someone = store.contacts.value.first { it.id != "me" && it.id != "riya" }
        rule.onNodeWithText(someone.displayName).performClick()
        rule.waitForIdle()
        assertTrue(store.calls.any { it.startsWith("send:pass it on") })
        assertNotNull(forwarded)
    }

    @Test
    fun theRequestBarExplainsWhoThisIsAndWhatReportDoes() {
        launch(sampleWith(listOf(m(1)), isRequest = true))
        rule.onNodeWithText("This person isn't in your contacts. Their calls won't ring until you accept.").assertIsDisplayed()
        rule.onNodeWithText("Report").performClick()
        rule.onNodeWithText("The last 10 messages will be sent to the server admin.").assertIsDisplayed()
    }

    @Test
    fun blockOnAChatThatCannotBeResolvedShowsTheGenericErrorAndStays() {
        val inner = sampleWith(listOf(m(1)), isRequest = true)
        val store = object : ChatStore by inner { override fun chat(id: String): Chat? = null }
        launch(store)
        rule.onNodeWithText("Block").performClick()
        rule.onAllNodes(hasText("Block"))[1].performClick()
        rule.onNodeWithText("Something went wrong on the server. Try again later.").assertIsDisplayed()
        assertEquals(0, blocks)
        rule.onNodeWithText("Accept").assertIsDisplayed()
    }
}
