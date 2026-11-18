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

    /** The name a sent video travels under: the picker's name when it has a video extension, else with the extension its mime gives (mp4 by default). */
    fun videoFileName(displayName: String?, mime: String?): String {
        val name = displayName?.trim().orEmpty()
        if (name.isEmpty()) return "video." + extensionForMime(mime)
        return if (extensionOf(name) in EXTENSIONS) name else name.trimEnd('.') + "." + extensionForMime(mime)
    }

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
