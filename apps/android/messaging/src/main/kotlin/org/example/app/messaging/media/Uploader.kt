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

    suspend fun uploadKeeping(plaintext: File): Uploaded = withContext(Dispatchers.IO) {
        tempDir.mkdirs()
        val temp = File(tempDir, "${UUID.randomUUID()}.enc")
        try {
            val blob = AttachmentCipher.encrypt(plaintext, temp)
            val ref = FileInputStream(temp).use { api.uploadAttachment(it, temp.length()) }
            // The server chose the id; it becomes a file name, so it is held to the rule a received id is.
            AttachmentIds.check(ref.id)
            Uploaded(Att(id = ref.id, key = blob.key, dg = blob.digest, sz = blob.size, csz = blob.cipherSize), temp)
        } catch (e: Throwable) {
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
