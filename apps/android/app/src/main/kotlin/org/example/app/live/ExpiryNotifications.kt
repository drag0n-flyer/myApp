package org.example.app.live

import android.content.Context
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.MessagePreview
import org.example.app.push.PushRuntime

/**
 * What an expiry does to delivered notifications. [unreadLeft] is what the sweeper reports: chats that lost an incoming
 * message, with the unread messages each has left. None left: the chat's message notification goes. Some left: it shows
 * those (and times out with the last of them). A missed call or a "not sent" notice is never touched.
 */
object ExpiryNotifications {
    fun apply(context: Context, store: ChatStore, unreadLeft: Map<String, Int>) {
        for ((chatId, left) in unreadLeft) {
            if (left <= 0) {
                PushRuntime.cancelChatMessages(context, chatId)
                continue
            }
            val chat = store.chat(chatId) ?: continue
            // A group is titled with its name and each line names its sender; a one-to-one chat with the contact.
            val title = if (chat.isGroup) {
                chat.title ?: context.getString(org.example.app.R.string.group_untitled)
            } else {
                val contact = chat.contactId?.let { store.contact(it) } ?: continue
                contact.displayName.ifEmpty { contact.username }
            }
            val remaining = chat.messages
                .filter { it.senderId != store.me.id && it.senderId != "system" && it.content !is MessageContent.System }
                .takeLast(left)
            if (remaining.isEmpty()) continue
            val expiresAt = if (remaining.all { it.expiresAt != null }) remaining.maxOf { it.expiresAt!!.toEpochMilli() } else null
            val lines = remaining.map { m ->
                val body = text(context, m.preview)
                val line = if (chat.isGroup) {
                    val who = store.personName(m.senderId) ?: context.getString(org.example.app.R.string.group_someone)
                    context.getString(org.example.app.R.string.group_sender_preview, who, body)
                } else {
                    body
                }
                line to m.sentAt.toEpochMilli()
            }
            PushRuntime.replaceChatLines(context, chatId, title, lines, expiresAt)
        }
    }

    private fun text(context: Context, preview: MessagePreview): String = when (preview) {
        is MessagePreview.Text -> preview.text
        is MessagePreview.Resource -> {
            val id = context.resources.getIdentifier(preview.key, "string", context.packageName)
            when {
                id == 0 -> preview.key
                preview.key == "preview_unsupported" -> context.getString(id, context.getString(org.example.app.R.string.app_name))
                else -> context.getString(id, *preview.args.toTypedArray())
            }
        }
    }
}
