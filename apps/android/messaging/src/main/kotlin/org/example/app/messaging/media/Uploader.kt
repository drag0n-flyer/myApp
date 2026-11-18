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

/**
 * Encrypts a file and uploads the ciphertext (Design 7). The ciphertext goes to a temporary file in [tempDir]. When
 * the upload failed it is deleted. When it worked and [blobDir] is set, the file moves there as
 * `<id>-<digest hex>.enc` (the name `Downloader` uses), so the sender holds the same encrypted copy a receiver
 * downloads; without [blobDir] it is deleted. The key never leaves the returned [Att].
 */
class Uploader(private val api: DeliveryApi, private val tempDir: File, private val blobDir: File? = null) {
    suspend fun upload(plaintext: File): Att = withContext(Dispatchers.IO) {
        tempDir.mkdirs()
        val temp = File(tempDir, "${UUID.randomUUID()}.enc")
        try {
            val blob = AttachmentCipher.encrypt(plaintext, temp)
            val ref = FileInputStream(temp).use { api.uploadAttachment(it, temp.length()) }
            val att = Att(id = ref.id, key = blob.key, dg = blob.digest, sz = blob.size, csz = blob.cipherSize)
            blobDir?.let { dir ->
                dir.mkdirs()
                val target = blobFile(dir, att)
                if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
            }
            att
        } finally {
            temp.delete()
        }
    }

    companion object {
        /** Where the ciphertext of [att] lives in [blobDir]: the same name `Downloader` gives a download. */
        fun blobFile(blobDir: File, att: Att): File =
            File(blobDir, "${att.id}-${att.dg.joinToString("") { String.format(Locale.ROOT, "%02x", it) }}.enc")
    }
}
