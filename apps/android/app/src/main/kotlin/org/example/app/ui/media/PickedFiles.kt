package org.example.app.ui.media

import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.example.app.core.chats.SafeFileName
import org.example.app.messaging.media.Downloader

/**
 * A file chosen in the system document picker is copied into the app's own storage before it is sent: the picker's
 * permission to read it ends with the screen, and the sender uploads from a path. Nothing about the file is trusted: not
 * its name, not its size, not the kind of URI it came as.
 */
object PickedFiles {
    /** The same ceiling a received attachment is held to. */
    const val MAX_BYTES = Downloader.DEFAULT_MAX_BYTES

    sealed interface Result {
        data class Copied(val file: File) : Result
        data object TooBig : Result
        data object Unreadable : Result
    }

    /** Only a `content:` URI from a document provider; a `file:` URI could name a file of this very app. */
    fun isFromPicker(uri: Uri): Boolean = uri.scheme == "content"

    /**
     * Copies what [open] returns into a new folder under [outDir], named from [rawName] once [SafeFileName] has cleaned it.
     * Stops at [maxBytes] (the part copied is deleted), and can be cancelled: nothing is left behind in either case, nor
     * when the source cannot be read. Runs off the main thread.
     */
    suspend fun copy(outDir: File, rawName: String?, maxBytes: Long = MAX_BYTES, open: () -> InputStream?): Result =
        withContext(Dispatchers.IO) {
            val name = SafeFileName.of(rawName, "file")
            outDir.mkdirs()
            val dir = File(outDir, UUID.randomUUID().toString())
            try {
                if (!dir.mkdirs()) return@withContext Result.Unreadable
                val dest = File(dir, name)
                if (dest.canonicalFile.parentFile != dir.canonicalFile) {
                    dir.deleteRecursively()
                    return@withContext Result.Unreadable
                }
                val input = open()
                if (input == null) {
                    dir.deleteRecursively()
                    return@withContext Result.Unreadable
                }
                var total = 0L
                input.use { source ->
                    dest.outputStream().use { target ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = source.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) {
                                dir.deleteRecursively()
                                return@withContext Result.TooBig
                            }
                            target.write(buffer, 0, n)
                        }
                    }
                }
                Result.Copied(dest)
            } catch (e: CancellationException) {
                dir.deleteRecursively()
                throw e
            } catch (e: Exception) {
                // Another app's provider can fail in any way (IOException, SecurityException, IllegalState...): never a crash.
                dir.deleteRecursively()
                Result.Unreadable
            }
        }

    private const val BUFFER = 64 * 1024
}
