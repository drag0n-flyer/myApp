package org.example.app.ui.media

import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.MediaState
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.isVideo
import org.example.app.media.VideoMeta

/** One page of the viewer: a message's file, and whether it plays as a video. */
data class ViewerItem(val id: String, val video: Boolean)

/** The photos, videos and files of one chat, read from the whole history for the viewer and the gallery. */
object ChatMediaIndex {
    private const val PAGE = 200

    /** A video is a file whose mime or name says so; there is no guessing from the bytes. */
    fun isVideo(message: Message): Boolean = (message.content as? MessageContent.File)?.isVideo == true

    /** Photos and videos: what the gallery's Media tab and the viewer show. */
    fun isMedia(message: Message): Boolean = message.content is MessageContent.Photo || isVideo(message)

    /** Every photo, video and file message of [chatId], oldest first. Reads the database: call it off the main thread. */
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

    /** What the viewer swipes over: photos and videos that are on this device. */
    fun viewerItems(media: List<Message>): List<ViewerItem> = media.filter { isMedia(it) && isLocal(it) }.map { ViewerItem(it.id, isVideo(it)) }
}

/** A picture to show for a downloaded photo (decoded) or video (a frame of it, with its length). Blocking: call it off the main thread. */
internal fun decodeMedia(file: java.io.File, video: Boolean, width: Int, height: Int): Pair<android.graphics.Bitmap?, Long?> =
    if (!video) {
        PhotoDecoder.decodeFit(file, width, height) to null
    } else {
        val info = VideoMeta.probe(file)
        if (info == null) null to null else VideoMeta.frame(file, width, height) to info.durationMs
    }
