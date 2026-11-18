package org.example.app.ui.media

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.example.app.core.chats.SafeFileName
import org.example.app.media.MediaCache

/**
 * Hands one file to another app: a plaintext copy is staged in a folder of its own, given a URI through the app's
 * `FileProvider`, and the receiving app gets read access to that one URI and nothing else.
 *
 * The provider (see `res/xml/file_paths.xml`) serves only [shareDir] and [cameraDir]. Both sit in the cache folders that
 * are emptied at start-up and when the account is removed.
 */
object SharedFiles {
    /** A staged copy older than this is deleted the next time one is staged. The other app may still be reading a younger one. */
    const val MAX_AGE_MS = 3_600_000L
    private const val OCTET_STREAM = "application/octet-stream"
    private const val APK = "application/vnd.android.package-archive"

    /** Copies of files being opened or shared. */
    fun shareDir(context: Context): File = File(MediaCache.openDir(context), "share")

    /** Where the system camera writes the photo it takes for us. */
    fun cameraDir(context: Context): File = File(MediaCache.outgoingDir(context), "camera")

    fun authority(context: Context): String = "${context.packageName}.files"

    /**
     * Copies [source] into its own new folder under [shareDir], named from [rawName] after [SafeFileName] has cleaned it,
     * so a hostile name cannot leave the folder and two files of the same name never collide. Folders older than
     * [MAX_AGE_MS] (against [now]) are removed first. The copy can be cancelled; nothing is left behind then. [source]
     * is not touched: closing it is the caller's job.
     */
    suspend fun stage(shareDir: File, source: File, rawName: String?, fallbackName: String, now: Long = System.currentTimeMillis()): File =
        withContext(Dispatchers.IO) {
            val name = SafeFileName.of(rawName, fallbackName)
            shareDir.mkdirs()
            shareDir.listFiles()?.forEach { if (it.isDirectory && it.lastModified() < now - MAX_AGE_MS) it.deleteRecursively() }
            val dir = File(shareDir, UUID.randomUUID().toString())
            if (!dir.mkdirs()) throw IOException("cannot create the share folder")
            val dest = File(dir, name)
            try {
                if (dest.canonicalFile.parentFile != dir.canonicalFile) throw IOException("name left the folder")
                source.inputStream().use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                        }
                    }
                }
            } catch (t: Throwable) {
                dir.deleteRecursively()
                throw t
            }
            dest
        }

    /** The content URI for a staged file. Throws for a file outside the folders the provider serves. */
    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /** From the extension only; a type a message states about itself is never used. */
    fun mimeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return OCTET_STREAM
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: OCTET_STREAM
    }

    /** Open with another app: read access to [uri] only. Null for a file that must never be handed on (an installable package). */
    fun viewIntent(uri: Uri, name: String): Intent? {
        val mime = mimeFor(name)
        if (mime == APK) return null
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** The share sheet's intent: read access to [uri] only. Null for an installable package, as for [viewIntent]. */
    fun sendIntent(uri: Uri, name: String): Intent? {
        val mime = mimeFor(name)
        if (mime == APK) return null
        return Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = ClipData.newRawUri(null, uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private const val BUFFER = 64 * 1024
}
