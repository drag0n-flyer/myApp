package org.example.app.core.chats

/** What the long-press menu on a message can offer, in menu order (spec 5.2). */
enum class MessageAction { Reply, React, Copy, Forward, Edit, Delete }

object MessageActions {
    /** The six reactions, in the order the menu shows them. */
    val reactionEmoji: List<String> = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    fun available(message: Message, isMine: Boolean): List<MessageAction> {
        val content = message.content
        when (content) {
            MessageContent.Deleted, MessageContent.Unsupported, is MessageContent.Call, is MessageContent.System -> return emptyList()
            else -> {}
        }
        val text = content is MessageContent.Text
        if (isMine && message.status == DeliveryStatus.Failed) {
            // Never left this device: nothing to reply to, react to, forward or edit.
            return buildList {
                if (text) add(MessageAction.Copy)
                add(MessageAction.Delete)
            }
        }
        return buildList {
            add(MessageAction.Reply)
            add(MessageAction.React)
            if (text) {
                add(MessageAction.Copy)
                add(MessageAction.Forward)
                if (isMine) add(MessageAction.Edit)
            }
            if (isMine) add(MessageAction.Delete)
        }
    }

    /**
     * True when [action] may still run on [current], the message as it is now (null when it is gone). A menu is built from
     * an earlier copy of the message; this decides whether the choice is still valid when it is made.
     */
    fun stillApplies(action: MessageAction, current: Message?, isMine: Boolean): Boolean =
        current != null && action in available(current, isMine)
}
