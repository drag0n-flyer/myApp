package org.example.app.core.notify

import java.time.Instant
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessagePreview

/** One line of a chat notification. */
data class Line(val text: String, val sentAt: Instant)

/**
 * What one chat's notification shows. `hideContent`: app lock is on, so the notification names nobody, shows
 * [NotificationPlan.HIDDEN_TEXT] and offers no Reply.
 */
data class ChatNotification(
    val chatId: String,
    val title: String,
    val lines: List<Line>,
    val silent: Boolean,
    val hideContent: Boolean,
)

object NotificationPlan {
    const val MAX_LINES = 6

    /** Same words as the `push_generic_body` string; the notifier shows its own translated copy. */
    const val HIDDEN_TEXT = "New message"

    fun forChat(
        chatId: String,
        unread: List<Message>,
        senderName: String,
        muted: Boolean,
        appLocked: Boolean,
        describe: (MessagePreview) -> String = { if (it is MessagePreview.Text) it.text else (it as MessagePreview.Resource).key },
    ): ChatNotification = ChatNotification(chatId, "", emptyList(), silent = false, hideContent = false)

    fun needsSummary(activeChatIds: Set<String>): Boolean = false
}
