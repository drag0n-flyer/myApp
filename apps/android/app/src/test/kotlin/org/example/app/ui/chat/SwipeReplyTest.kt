package org.example.app.ui.chat

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.Chat
import org.example.app.core.chats.ChatEncryption
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.core.chats.SystemEvent
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h700dp")
class SwipeReplyTest {
    @get:Rule val rule = createComposeRule()

    private val start = Instant.now().minus(10, ChronoUnit.DAYS)

    private fun m(n: Int, sender: String = "riya", text: String = "message $n", content: MessageContent = MessageContent.Text(text)) =
        Message("m$n", sender, start.plus(n.toLong(), ChronoUnit.MINUTES), content, DeliveryStatus.Read, null, emptyList())

    private fun launch(messages: List<Message>) {
        val data = SampleData.make(Instant.now())
        val store: ChatStore = SampleChatStore(data.copy(chats = listOf(Chat("chat-riya", "riya", ChatEncryption.EndToEnd, emptySet(), 0, messages, false))))
        val vm = ChatViewModel(store, "chat-riya", NetworkType.Wifi, Dispatchers.Unconfined) { AutoDownloadSettings() }
        rule.setContent {
            AppTheme { ChatScreen(vm, myId = "me", onBack = {}, onOpenInfo = {}, onCall = null, onAccept = {}, onBlock = {}, onReport = {}, onForward = {}, highlightMessageId = null) }
        }
    }

    @Test
    fun swipingABubbleTowardTheTrailingSidePastTheThresholdStartsAReply() {
        launch(listOf(m(1, text = "hello there")))
        rule.onNodeWithText("hello there").performTouchInput { swipeRight(startX = centerX, endX = centerX + 150f) }
        rule.onNodeWithText("Replying to Riya").assertIsDisplayed()
    }

    @Test
    fun releasingBetweenTheThresholdAndTheMaximumStillReplies() {
        launch(listOf(m(1, text = "hello there")))
        rule.onNodeWithText("hello there").performTouchInput { swipeRight(startX = centerX, endX = centerX + 52.dp.toPx() + viewConfiguration.touchSlop) }
        rule.onNodeWithText("Replying to Riya").assertIsDisplayed()
    }

    @Test
    fun aShortSwipeSpringsBackAndDoesNothing() {
        launch(listOf(m(1, text = "hello there")))
        rule.onNodeWithText("hello there").performTouchInput { swipeRight(startX = centerX, endX = centerX + 30f) }
        rule.onNodeWithText("Replying to Riya").assertDoesNotExist()
    }

    @Test
    fun swipingTowardTheLeadingSideDoesNothing() {
        launch(listOf(m(1, text = "hello there")))
        rule.onNodeWithText("hello there").performTouchInput { swipeLeft(startX = centerX, endX = centerX - 150f) }
        rule.onNodeWithText("Replying to Riya").assertDoesNotExist()
    }

    @Test
    fun aVerticalFlingStillScrollsTheList() {
        launch((1..300).map { m(it) })
        rule.onNodeWithText("message 300").assertIsDisplayed()
        rule.onNode(hasScrollAction()).performTouchInput { swipeDown(startY = top + 50f, endY = bottom - 50f) }
        rule.onNodeWithText("message 300").assertDoesNotExist()
        rule.onNodeWithText("Replying to Riya").assertDoesNotExist()
    }

    @Test
    fun aSystemLineIsNotSwipeable() {
        launch(listOf(m(1, content = MessageContent.System(SystemEvent.MessagesPaused))))
        rule.onNode(hasScrollAction()).performTouchInput { swipeRight(startX = centerX, endX = centerX + 200f) }
        rule.onNodeWithText("Replying to Riya").assertDoesNotExist()
    }

    @Test
    fun aDeletedMessageIsNotSwipeable() {
        launch(listOf(m(1, content = MessageContent.Deleted)))
        rule.onNodeWithText("This message was deleted").performTouchInput { swipeRight(startX = centerX, endX = centerX + 150f) }
        rule.onNodeWithText("Replying to Riya").assertDoesNotExist()
    }

    @Test
    fun theReplyActionIsAlsoACustomAccessibilityActionOnTheBubble() {
        launch(listOf(m(1, text = "hello there")))
        val node = rule.onNodeWithText("hello there").fetchSemanticsNode()
        val labels = generateSequence(node) { it.parent }
            .mapNotNull { it.config.getOrNull(SemanticsActions.CustomActions) }.flatten().map { it.label }.toList()
        kotlin.test.assertTrue("Reply" in labels, "custom actions: $labels")
    }
}
