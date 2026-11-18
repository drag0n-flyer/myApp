package org.example.app.ui.media

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import org.example.app.media.VideoMeta

/**
 * Saves a decrypted photo or video to the device's Pictures or Movies folder through `MediaStore` (no permission is needed
 * to add a file on Android 10 and later). Blocking: call it off the main thread. The caller closes (deletes) [file].
 */
object MediaSaver {
    /** What the bytes are: an image type the platform decodes, a video the platform reads, or null. */
    internal fun kindOf(file: File): Pair<String, Boolean>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth > 0 && bounds.outMimeType != null) return bounds.outMimeType!! to false
        if (VideoMeta.probe(file) != null) return "video/mp4" to true
        return null
    }

    /** True when it was saved. A file that is neither a photo nor a video, or a store that refuses, is false. */
    fun save(context: Context, file: File, now: Long = System.currentTimeMillis()): Boolean {
        val (mime, video) = kindOf(file) ?: return false
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (video) "mp4" else "jpg"
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${if (video) "VID" else "IMG"}_$now.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies" else "Pictures")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = try { resolver.insert(collection, values) } catch (e: Exception) { null } ?: return false
        return try {
            (resolver.openOutputStream(uri) ?: throw IOException("no stream")).use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            false
        }
    }
}
