package org.example.app.messaging.media

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.example.app.contracts.DeliveryApi
import org.example.app.messaging.content.Att
import org.example.app.messaging.crypto.AttachmentCipher
import org.example.app.messaging.crypto.AttachmentException

/** The server sent fewer bytes than the attachment has; what arrived is kept and the next call resumes. */
class IncompleteDownloadException : IOException("incomplete download")

/**
 * Downloads and decrypts one attachment (Design 7, 9). The ciphertext is fetched in `Range` continuation into
 * `<id>.enc.part` inside [dir] and kept when the transfer stops, so the next call resumes at the end of what is
 * there. Only a complete file whose SHA-256 matches `att.dg` is decrypted, into a temporary file that is moved to
 * the target only when every chunk passed. The ciphertext part file is always removed once the file is complete
 * (decrypted or rejected). No plaintext is ever left in [dir].
 */
class Downloader(private val api: DeliveryApi, private val dir: File) {
    /** Returns [to] on success. A failed digest or chunk deletes the partial ciphertext, so a retry starts over. */
    suspend fun download(att: Att, to: File): File = withContext(Dispatchers.IO) {
        // The id comes from another person's message: it must never become a path.
        require(ID.matches(att.id)) { "bad attachment id" }
        dir.mkdirs()
        val part = File(dir, "${att.id}.enc.part")
        if (part.length() > att.csz) part.delete()
        try {
            fetch(att, part)
            AttachmentCipher.decrypt(part, to, att.key, att.dg)
            part.delete()
            to
        } catch (e: AttachmentException) {
            part.delete()
            throw e
        }
    }

    private suspend fun fetch(att: Att, part: File) {
        val have = part.length()
        if (have >= att.csz) return
        val stream = api.downloadAttachment(att.id, have)
        var remaining = att.csz - have
        stream.use { input ->
            FileOutputStream(part, true).use { out ->
                val buf = ByteArray(16 * 1024)
                while (remaining > 0) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
        if (remaining > 0) throw IncompleteDownloadException()
    }

    private companion object {
        val ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
