package org.example.app.messaging.media

import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.app.contracts.DeliveryApi
import org.example.app.messaging.content.Att
import org.example.app.messaging.crypto.AttachmentCipher

/** A finished upload whose ciphertext still waits in the temp folder: [moveTo] keeps it, [discard] deletes it. */
class Uploaded internal constructor(val att: Att, private val temp: File) {
    /** Moves the ciphertext to `<id>-<digest hex>.enc` (the name `Downloader` uses) in [blobDir] and returns it. */
    fun moveTo(blobDir: File): File {
        blobDir.mkdirs()
        val target = Uploader.blobFile(blobDir, att)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    fun discard() {
        temp.delete()
    }
}

/**
 * Encrypts a file and uploads the ciphertext (Design 7). The ciphertext goes to a temporary file in [tempDir]. [upload]
 * deletes it when done; [uploadKeeping] leaves it for the caller to keep ([Uploaded.moveTo], so the sender holds the
 * same encrypted copy a receiver downloads) or [Uploaded.discard]. A failed upload deletes it either way. The key never
 * leaves the returned [Att].
 */
class Uploader(private val api: DeliveryApi, private val tempDir: File) {
    suspend fun upload(plaintext: File): Att = uploadKeeping(plaintext).also { it.discard() }.att

    /** [onProgress] gets the fraction of the ciphertext handed to the server so far (0.0 to 1.0), from the uploading thread. */
    suspend fun uploadKeeping(plaintext: File, onProgress: (Double) -> Unit = {}): Uploaded {
        tempDir.mkdirs()
        val temp = File(tempDir, "${UUID.randomUUID()}.enc")
        try {
            return withContext(Dispatchers.IO) {
                val blob = AttachmentCipher.encrypt(plaintext, temp)
                val ref = ProgressInputStream(FileInputStream(temp), temp.length(), onProgress).use { api.uploadAttachment(it, temp.length()) }
                // The server chose the id; it becomes a file name, so it is held to the rule a received id is.
                AttachmentIds.check(ref.id)
                Uploaded(Att(id = ref.id, key = blob.key, dg = blob.digest, sz = blob.size, csz = blob.cipherSize), temp)
            }
        } catch (e: Throwable) {
            // Also when the caller was cancelled just as the upload finished: the result is lost, so the ciphertext goes.
            temp.delete()
            throw e
        }
    }

    companion object {
        /** Where the ciphertext of [att] lives in [blobDir]: the same name `Downloader` gives a download. */
        fun blobFile(blobDir: File, att: Att): File =
            File(blobDir, "${att.id}-${att.dg.joinToString("") { String.format(Locale.ROOT, "%02x", it) }}.enc")
    }
}

/** Reports how much of a stream of [total] bytes has been read, as a fraction. A total of zero or less reports 1.0 once read. */
class ProgressInputStream(inner: java.io.InputStream, private val total: Long, private val onProgress: (Double) -> Unit) : java.io.FilterInputStream(inner) {
    private var read = 0L

    private fun note(n: Int) {
        if (n <= 0) return
        read += n
        onProgress(if (total <= 0) 1.0 else (read.toDouble() / total).coerceIn(0.0, 1.0))
    }

    override fun read(): Int = super.read().also { if (it >= 0) note(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { note(it) }
}
