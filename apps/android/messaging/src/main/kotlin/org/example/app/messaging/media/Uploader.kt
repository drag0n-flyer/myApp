package org.example.app.messaging.media

import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.app.contracts.DeliveryApi
import org.example.app.messaging.content.Att
import org.example.app.messaging.crypto.AttachmentCipher

/**
 * Encrypts a file and uploads the ciphertext (Design 7). The ciphertext goes to a temporary file in [tempDir]
 * that is deleted when the upload ends, whether it worked or not. The key never leaves the returned [Att].
 */
class Uploader(private val api: DeliveryApi, private val tempDir: File) {
    suspend fun upload(plaintext: File): Att = withContext(Dispatchers.IO) {
        tempDir.mkdirs()
        val temp = File(tempDir, "${UUID.randomUUID()}.enc")
        try {
            val blob = AttachmentCipher.encrypt(plaintext, temp)
            val ref = FileInputStream(temp).use { api.uploadAttachment(it, temp.length()) }
            Att(id = ref.id, key = blob.key, dg = blob.digest, sz = blob.size, csz = blob.cipherSize)
        } finally {
            temp.delete()
        }
    }
}
