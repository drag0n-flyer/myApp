package org.example.app.messaging.media

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
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
 * - [download] fetches the ciphertext in `Range` continuation into `<id>-<digest hex>.enc.part` inside [blobDir] and keeps it
 *   when the transfer stops, so the next call resumes. A complete file whose SHA-256 matches `att.dg` is renamed
 *   to `<id>-<digest hex>.enc` and stays there, encrypted with the attachment key (the key lives only in the message).
 * - [open] decrypts the blob into [openDir] and returns an [OpenedAttachment]; closing it deletes the plaintext.
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
        checkShape(att)
        lock(att.id).withLock { ensureBlob(att) }
    }

    /** Downloads when needed, then decrypts into [openDir]. Close the result when the item is no longer shown. */
    suspend fun open(att: Att): OpenedAttachment = withContext(Dispatchers.IO) {
        checkId(att.id)
        checkShape(att)
        // The plaintext file is named for the id, the digest and the key, so a message with another key never
        // shares (or deletes) the file that another message shows.
        val name = "${att.id}-${hex(att.dg)}-${hex(MessageDigest.getInstance("SHA-256").digest(att.key)).take(16)}"
        lock(att.id).withLock {
            val blob = ensureBlob(att)
            val plain = File(openDir, name)
            val shared = synchronized(viewers) {
                val n = viewers[name] ?: 0
                if (n > 0 && plain.exists()) { viewers[name] = n + 1; true } else false
            }
            if (!shared) {
                openDir.mkdirs()
                // The blob passed its digest check, so a failure here is the message's fault (wrong key); the blob
                // may belong to the real message and is never deleted on this path.
                AttachmentCipher.decrypt(blob, plain, att.key, att.dg)
                // Count up, never reset: a handle opened before the file was cleared still holds a count.
                synchronized(viewers) { viewers[name] = (viewers[name] ?: 0) + 1 }
            }
            OpenedAttachment(plain) {
                synchronized(viewers) {
                    val left = (viewers[name] ?: 1) - 1
                    if (left <= 0) { viewers.remove(name); plain.delete() } else viewers[name] = left
                }
            }
        }
    }

    /**
     * Deletes the files in [openDir] that no viewer shows (leftovers of a killed process, temp files). Safe to call
     * while items are shown; also deletes partial downloads (`*.enc.part`) in [blobDir], so call it at app start,
     * before any download begins.
     */
    fun sweepOpen() {
        blobDir.listFiles()?.forEach { if (it.name.endsWith(".enc.part")) it.delete() }
        val shown = synchronized(viewers) { viewers.keys.toSet() }
        openDir.listFiles()?.forEach { if (it.name !in shown) it.deleteRecursively() }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }

    private fun lock(id: String) = locks.computeIfAbsent(id) { Mutex() }

    private fun checkId(id: String) {
        // The id comes from another person's message: it must never become a path.
        require(ID.matches(id)) { "bad attachment id" }
    }

    /** Digest and key are 32 bytes; anything else is malformed, and `dg` becomes part of a file name. */
    private fun checkShape(att: Att) {
        if (att.dg.size != 32 || att.key.size != 32) throw AttachmentSizeException("malformed attachment")
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
        // Keyed by id and digest: a blob is reused only for a message with the same digest, and a message with other
        // values never touches it (it can only fetch and verify its own file).
        val stem = "${att.id}-${hex(att.dg)}"
        val blob = File(blobDir, "$stem.enc")
        if (blob.length() == att.csz) return blob
        val part = File(blobDir, "$stem.enc.part")
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
