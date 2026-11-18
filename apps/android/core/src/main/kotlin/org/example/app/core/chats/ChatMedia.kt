package org.example.app.core.chats

import java.util.Locale

/** What makes a file a video, the same on every platform: its mime starts with `video/`, or its name ends in a video extension. */
object ChatMedia {
    private val EXTENSIONS = setOf("mp4", "m4v", "mov", "3gp", "webm", "mkv")

    private fun extensionOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        return if (dot < 0 || dot == name.lastIndex) null else name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun isVideo(name: String, mime: String): Boolean =
        mime.trim().lowercase(Locale.ROOT).startsWith("video/") || extensionOf(name) in EXTENSIONS

    private fun extensionForMime(mime: String?): String = when (mime?.trim()?.lowercase(Locale.ROOT)) {
        "video/quicktime" -> "mov"
        "video/3gpp" -> "3gp"
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        "video/x-m4v" -> "m4v"
        else -> "mp4"
    }

    /**
     * The name a sent video travels under: generic, `video-<8 hex>.<ext>`, never the picker's own name (it can reveal the
     * device and the time). The extension is the picker name's when it is a video one, else the mime's (mp4 by default).
     */
    fun videoFileName(displayName: String?, mime: String?, hex: String = randomHex()): String {
        val ext = displayName?.trim()?.let { extensionOf(it) }?.takeIf { it in EXTENSIONS } ?: extensionForMime(mime)
        return "video-$hex.$ext"
    }

    private fun randomHex(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(8)

    /** The extension a saved video keeps: its name's when it is a video one, else mp4. */
    fun videoExtension(name: String): String = extensionOf(name)?.takeIf { it in EXTENSIONS } ?: "mp4"

    /** The mime a video with this name is sent with. */
    fun videoMime(name: String): String = when (extensionOf(name)) {
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "m4v" -> "video/x-m4v"
        else -> "video/mp4"
    }
}

/** True when this file message is a video. */
val MessageContent.File.isVideo: Boolean get() = ChatMedia.isVideo(name, mime)
