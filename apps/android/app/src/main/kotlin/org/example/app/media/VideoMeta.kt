package org.example.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import kotlin.math.max

/** What the platform knows about a video: its size and length, and one frame of it. Blocking: call it off the main thread. */
object VideoMeta {
    data class Info(val width: Int, val height: Int, val durationMs: Long)

    /** Null when [file] is not a video the platform can read (a photo, a file, a damaged video). */
    fun probe(file: File): Info? = withRetriever({ it.setDataSource(file.path) }) { info(it) }

    fun probe(context: Context, uri: Uri): Info? = withRetriever({ it.setDataSource(context, uri) }) { info(it) }

    /** A frame near the start, scaled to fit [maxWidth] x [maxHeight]. Null when there is none. */
    fun frame(file: File, maxWidth: Int, maxHeight: Int): Bitmap? = withRetriever({ it.setDataSource(file.path) }) { frameOf(it, maxWidth, maxHeight) }

    fun frame(context: Context, uri: Uri, maxWidth: Int, maxHeight: Int): Bitmap? =
        withRetriever({ it.setDataSource(context, uri) }) { frameOf(it, maxWidth, maxHeight) }

    private fun info(r: MediaMetadataRetriever): Info? {
        if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return null
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        return Info(w, h, max(0L, d))
    }

    private fun frameOf(r: MediaMetadataRetriever, maxWidth: Int, maxHeight: Int): Bitmap? =
        r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, max(1, maxWidth), max(1, maxHeight))

    private fun <T> withRetriever(open: (MediaMetadataRetriever) -> Unit, read: (MediaMetadataRetriever) -> T?): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            open(retriever)
            read(retriever)
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }
}

/** "0:07", "12:30", "1:02:03". */
fun videoClock(durationMs: Long): String {
    val total = max(0L, durationMs) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
