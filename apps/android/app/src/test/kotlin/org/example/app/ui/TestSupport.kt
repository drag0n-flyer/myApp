package org.example.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.SemanticsMatcher
import java.time.Instant
import org.example.app.core.chats.Chat
import org.example.app.core.chats.ChatEncryption
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData

/** Every text and content description on screen, including nodes merged into their parents. */
fun ComposeContentTestRule.allLabels(): List<String> =
    onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

fun msg(id: String, sender: String, content: MessageContent, status: DeliveryStatus = DeliveryStatus.Read, reply: String? = null) =
    Message(id, sender, Instant.now(), content, status, reply, emptyList())

/** A store whose only chat is `chat-riya` with the given messages. */
fun storeWith(
    messages: List<Message>,
    encryption: ChatEncryption = ChatEncryption.EndToEnd,
    modeMismatch: Boolean = false,
): SampleChatStore {
    val data = SampleData.make(Instant.now())
    val chat = Chat("chat-riya", "riya", encryption, emptySet(), 0, messages, false, modeMismatch)
    return SampleChatStore(data.copy(chats = listOf(chat)))
}
