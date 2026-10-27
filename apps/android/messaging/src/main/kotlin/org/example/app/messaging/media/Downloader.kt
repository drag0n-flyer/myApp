package org.example.app.messaging.media

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.example.app.contracts.DeliveryApi
import org.example.app.contracts.ServerException
import org.example.app.messaging.content.Att
import org.example.app.messaging.crypto.AttachmentCipher
import org.example.app.messaging.crypto.AttachmentException

/** The server sent fewer bytes than the attachment has; what arrived is kept and the next call resumes. */
class IncompleteDownloadException : IOException("incomplete download")

/** `csz` is over the size limit, or does not match `sz` (a sender can put anything in a message). Nothing was fetched. */
class AttachmentSizeException(message: String) : IOException(message)

/** A decrypted attachment shown to the user. [close] deletes the plaintext when the last viewer of it is done. */
class OpenedAttachment internal constructor(val file: File, private val onClose: () -> Unit) : Closeable {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) onClose() }
}

/**
 * Downloads and opens attachments (Design 4, 7, 9).
 *
 * - [download] fetches the ciphertext in `Range` continuation into `<id>.enc.part` inside [blobDir] and keeps it
 *   when the transfer stops, so the next call resumes. A complete file whose SHA-256 matches `att.dg` is renamed
 *   to `<id>.enc` and stays there, encrypted with the attachment key (the key lives only in the message).
 * - [open] decrypts `<id>.enc` into [openDir] and returns an [OpenedAttachment]; closing it deletes the plaintext.
 *   [sweepOpen] removes whatever a killed process left in [openDir]; call it once at app start.
 * - Calls for the same attachment id are serialized, so an auto-download and a tap fetch it once and share one
 *   plaintext file. Use one instance per process.
 * - `csz` is checked against [maxBytes] and against `sz` before any byte is fetched.
 */
class Downloader(
    private val api: DeliveryApi,
    private val blobDir: File,
    private val openDir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val viewers = HashMap<String, Int>()

    /** Makes sure the verified, still encrypted file is on disk and returns it. */
    suspend fun download(att: Att): File = withContext(Dispatchers.IO) {
        checkId(att.id)
        lock(att.id).withLock { ensureBlob(att) }
    }

    /** Downloads when needed, then decrypts into [openDir]. Close the result when the item is no longer shown. */
    suspend fun open(att: Att): OpenedAttachment = withContext(Dispatchers.IO) {
        checkId(att.id)
        lock(att.id).withLock {
            val blob = ensureBlob(att)
            val plain = File(openDir, att.id)
            val shared = synchronized(viewers) {
                val n = viewers[att.id] ?: 0
                if (n > 0 && plain.exists()) { viewers[att.id] = n + 1; true } else false
            }
            if (!shared) {
                openDir.mkdirs()
                try {
                    AttachmentCipher.decrypt(blob, plain, att.key, att.dg)
                } catch (e: AttachmentException) {
                    // The blob matched the digest but does not decrypt with this key: it can never work.
                    blob.delete()
                    throw e
                }
                synchronized(viewers) { viewers[att.id] = 1 }
            }
            OpenedAttachment(plain) {
                synchronized(viewers) {
                    val left = (viewers[att.id] ?: 1) - 1
                    if (left <= 0) { viewers.remove(att.id); plain.delete() } else viewers[att.id] = left
                }
            }
        }
    }

    /** Deletes every file in [openDir]. For app start, when nothing can be shown yet. */
    fun sweepOpen() {
        synchronized(viewers) { viewers.clear() }
        openDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun lock(id: String) = locks.computeIfAbsent(id) { Mutex() }

    private fun checkId(id: String) {
        // The id comes from another person's message: it must never become a path.
        require(ID.matches(id)) { "bad attachment id" }
    }

    private fun checkSizes(att: Att) {
        if (att.sz < 0 || att.csz < 0 || att.csz > maxBytes) throw AttachmentSizeException("attachment too large")
        // sz <= csz <= maxBytes here, so sealedSize cannot overflow.
        if (att.sz > att.csz || AttachmentCipher.sealedSize(att.sz) != att.csz) throw AttachmentSizeException("size mismatch")
    }

    /** Caller holds the id's lock. */
    private suspend fun ensureBlob(att: Att): File {
        checkSizes(att)
        blobDir.mkdirs()
        val blob = File(blobDir, "${att.id}.enc")
        if (blob.length() == att.csz) return blob
        blob.delete()
        val part = File(blobDir, "${att.id}.enc.part")
        if (part.length() > att.csz) part.delete()
        try {
            try {
                fetch(att, part)
            } catch (e: ServerException) {
                // A server that ignores Range cannot resume: start again from zero, once.
                if (e.code != "bad_range" || part.length() == 0L) throw e
                part.delete()
                fetch(att, part)
            }
            AttachmentCipher.checkDigest(part, att.dg)
        } catch (e: AttachmentException) {
            part.delete()
            throw e
        }
        if (!part.renameTo(blob)) throw IOException("cannot store the attachment")
        return blob
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

    companion object {
        /** The server's `limits.attachmentBytes` default (100 MiB). Pass the value from server info when known. */
        const val DEFAULT_MAX_BYTES = 104_857_600L
        private val ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
