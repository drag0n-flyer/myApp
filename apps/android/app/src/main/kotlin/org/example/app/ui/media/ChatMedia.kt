package org.example.app.ui.media

import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.MediaState
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent

/** The photos, videos and files of one chat, read from the whole history for the viewer and the gallery. */
object ChatMedia {
    private const val PAGE = 200

    /** Every photo/video and file message of [chatId], oldest first. Reads the database: call it off the main thread. */
    fun load(store: ChatStore, chatId: String): List<Message> {
        val found = ArrayList<Message>()
        var before: String? = null
        val seen = HashSet<String>()
        while (true) {
            val page = store.messages(chatId, before, PAGE)
            if (page.isEmpty()) break
            // A store that repeats itself must not loop for ever.
            val fresh = page.filter { seen.add(it.id) }
            if (fresh.isEmpty()) break
            found.addAll(0, fresh.filter { it.content is MessageContent.Photo || it.content is MessageContent.File })
            before = page.first().id
            if (page.size < PAGE) break
        }
        return found
    }

    fun isLocal(message: Message): Boolean = message.media?.phase is MediaState.Phase.Local

    /** The ids the viewer swipes over: photos and videos that are on this device. */
    fun viewerIds(media: List<Message>): List<String> = media.filter { it.content is MessageContent.Photo && isLocal(it) }.map { it.id }
}
