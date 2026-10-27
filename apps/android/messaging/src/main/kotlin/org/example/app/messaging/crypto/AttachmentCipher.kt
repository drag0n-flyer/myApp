package org.example.app.messaging.crypto

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What [AttachmentCipher.encrypt] returns: the key and the values that go into the `Att` of the message. */
class EncryptedBlob(val key: ByteArray, val digest: ByteArray, val size: Long, val cipherSize: Long)

sealed class AttachmentException(message: String) : Exception(message) {
    /** The key is not 32 bytes. */
    class BadKey : AttachmentException("bad key")

    /** The ciphertext's SHA-256 differs from the digest in the message. Checked before any plaintext is written. */
    class Digest : AttachmentException("digest mismatch")

    /** Not a valid chunked ciphertext (empty, or a chunk shorter than its tag). */
    class Format : AttachmentException("bad format")

    /** A chunk failed authentication: tampered, reordered, truncated or from another file. */
    class Authentication : AttachmentException("authentication failed")
}

/**
 * Chunked AES-256-GCM attachment encryption (Design 7, the STREAM construction), the same bytes as the iPhone:
 * - 65 536 bytes of plaintext per chunk; every chunk adds a 16-byte tag.
 * - nonce = 4 zero bytes ‖ 8-byte big-endian chunk index.
 * - AAD = one byte, 0x01 for the last chunk and 0x00 otherwise, so a truncated file fails.
 * - digest = SHA-256 over the whole ciphertext.
 * An empty file is one empty last chunk (16 bytes). Memory use does not depend on file size.
 *
 * Uses only the platform's `javax.crypto` (AES/GCM/NoPadding). The tag check is done by the provider and is
 * constant time. A failed chunk releases no plaintext.
 */
object AttachmentCipher {
    const val CHUNK_SIZE = 65_536
    const val TAG_SIZE = 16
    const val SEALED_CHUNK_SIZE = CHUNK_SIZE + TAG_SIZE
    private const val KEY_SIZE = 32

    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(KEY_SIZE).also { random.nextBytes(it) }

    internal fun nonce(index: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[4 + i] = (index ushr (56 - 8 * i)).toByte()
        return n
    }

    internal fun aad(last: Boolean): ByteArray = byteArrayOf(if (last) 1 else 0)

    private fun cipher(mode: Int, key: ByteArray, index: Long, last: Boolean): Cipher {
        if (key.size != KEY_SIZE) throw AttachmentException.BadKey()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce(index)))
        c.updateAAD(aad(last))
        return c
    }

    /**
     * Encrypts [input] to [output] with [key] (a fresh random key by default) and returns the key, the digest of
     * what was written and the sizes. Neither stream is closed.
     */
    fun encrypt(input: InputStream, output: OutputStream, key: ByteArray = newKey()): EncryptedBlob {
        if (key.size != KEY_SIZE) throw AttachmentException.BadKey()
        val sha = MessageDigest.getInstance("SHA-256")
        var size = 0L
        var cipherSize = 0L
        var index = 0L
        var current = readFully(input, CHUNK_SIZE)
        while (true) {
            // Look one chunk ahead: a chunk is the last one only when nothing follows it.
            val next = if (current.size == CHUNK_SIZE) readFully(input, CHUNK_SIZE) else ByteArray(0)
            val last = next.isEmpty()
            val sealed = cipher(Cipher.ENCRYPT_MODE, key, index, last).doFinal(current)
            output.write(sealed)
            sha.update(sealed)
            size += current.size
            cipherSize += sealed.size
            if (last) break
            current = next
            index++
        }
        return EncryptedBlob(key, sha.digest(), size, cipherSize)
    }

    fun encrypt(file: File, to: File, key: ByteArray = newKey()): EncryptedBlob =
        try {
            FileInputStream(file).use { i -> FileOutputStream(to).use { o -> encrypt(i, o, key) } }
        } catch (t: Throwable) {
            to.delete()
            throw t
        }

    /**
     * Checks the digest of the whole ciphertext in [input], then decrypts it into [output]. Nothing is written
     * when the digest differs. A chunk that fails authentication throws [AttachmentException.Authentication];
     * [output] then holds only chunks that did pass, so the caller must discard it (see the `File` overload).
     */
    fun decrypt(input: File, output: OutputStream, key: ByteArray, digest: ByteArray) {
        if (key.size != KEY_SIZE) throw AttachmentException.BadKey()
        val sha = MessageDigest.getInstance("SHA-256")
        var length = 0L
        FileInputStream(input).use { i ->
            val buf = ByteArray(8192)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                sha.update(buf, 0, n)
                length += n
            }
        }
        // MessageDigest.isEqual compares in constant time.
        if (!MessageDigest.isEqual(sha.digest(), digest)) throw AttachmentException.Digest()
        if (length < TAG_SIZE) throw AttachmentException.Format()
        FileInputStream(input).use { decryptChunks(it, output, key, 0) }
    }

    /**
     * Decrypts into [to] through a temporary file that is moved into place only when every chunk passed.
     * On any failure no plaintext is left behind.
     */
    fun decrypt(input: File, to: File, key: ByteArray, digest: ByteArray) {
        val temp = File(to.path + ".part")
        temp.delete()
        try {
            FileOutputStream(temp).use { decrypt(input, it, key, digest) }
            to.delete()
            if (!temp.renameTo(to)) throw java.io.IOException("cannot move decrypted file")
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    /**
     * Decrypts sealed chunks from [input], whose first byte is the start of chunk [firstIndex] (so a ranged
     * download that starts at byte `firstIndex * 65 552` resumes in the middle of a file). The end of [input]
     * must be the end of the ciphertext: the last chunk is the one with nothing after it. The digest is not
     * checked here; callers that hold the whole ciphertext use [decrypt].
     */
    fun decryptChunks(input: InputStream, output: OutputStream, key: ByteArray, firstIndex: Long = 0) {
        if (key.size != KEY_SIZE) throw AttachmentException.BadKey()
        var index = firstIndex
        var current = readFully(input, SEALED_CHUNK_SIZE)
        while (true) {
            if (current.size < TAG_SIZE) throw AttachmentException.Format()
            val next = if (current.size == SEALED_CHUNK_SIZE) readFully(input, SEALED_CHUNK_SIZE) else ByteArray(0)
            val last = next.isEmpty()
            val plain = try {
                cipher(Cipher.DECRYPT_MODE, key, index, last).doFinal(current)
            } catch (e: GeneralSecurityException) {
                throw AttachmentException.Authentication()
            }
            output.write(plain)
            plain.fill(0)
            if (last) return
            current = next
            index++
        }
    }

    /** Reads exactly [count] bytes, or fewer only at the end of the stream. */
    private fun readFully(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var n = 0
        while (n < count) {
            val r = input.read(buf, n, count - n)
            if (r < 0) break
            n += r
        }
        return if (n == count) buf else buf.copyOf(n)
    }
}
