package org.example.app.core.archive

import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

sealed class ArchiveException(message: String) : Exception(message) {
    /** The key is not 32 bytes. */
    class BadKey : ArchiveException("bad key")

    /** Not an archive: wrong magic or version, missing final chunk, or a malformed record. */
    class Format : ArchiveException("bad format")

    /** A chunk failed authentication: tampered, reordered, truncated, extended, or the wrong key. */
    class Authentication : ArchiveException("authentication failed")
}

/**
 * Streaming AES-256-GCM archive encryption, format `docs/protocol/archive.md` (archive v1):
 * header = "APPARCH1" ‖ 0x01 ‖ 7-byte random nonce prefix; then one record per 1 MiB of plaintext:
 * u32be(length) ‖ GCM(ciphertext ‖ 16-byte tag). Chunk i uses nonce = prefix ‖ u32be(i) ‖ flag and
 * AAD = header ‖ u32be(i) ‖ flag, where flag is 0x01 on the last chunk and 0x00 otherwise.
 * An empty archive is one empty final chunk.
 *
 * Memory use does not depend on size. [decrypt] writes each chunk only after it is authenticated, but
 * earlier chunks are already written when a later one fails: the caller must discard the output on any
 * exception. Only `javax.crypto` is used.
 */
object ArchiveCipher {
    const val CHUNK_SIZE = 1 shl 20
    const val TAG_SIZE = 16
    const val KEY_SIZE = 32
    const val PREFIX_SIZE = 7
    const val HEADER_SIZE = 16
    private val MAGIC = "APPARCH1".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(KEY_SIZE).also { random.nextBytes(it) }

    fun encrypt(input: InputStream, output: OutputStream, key: ByteArray) {
        encryptWithPrefix(input, output, key, ByteArray(PREFIX_SIZE).also { random.nextBytes(it) })
    }

    /** The nonce prefix is fixed by the caller only for test vectors; production code uses [encrypt]. */
    internal fun encryptWithPrefix(input: InputStream, output: OutputStream, key: ByteArray, prefix: ByteArray) {
        if (key.size != KEY_SIZE) throw ArchiveException.BadKey()
        require(prefix.size == PREFIX_SIZE)
        val header = MAGIC + byteArrayOf(VERSION) + prefix
        output.write(header)
        val src = PushbackInputStream(input, 1)
        var index = 0L
        while (true) {
            val chunk = readUpTo(src, CHUNK_SIZE)
            val last = chunk.size < CHUNK_SIZE || !hasMore(src)
            val sealed = run(Cipher.ENCRYPT_MODE, key, header, prefix, index, last, chunk)
            output.write(u32(sealed.size.toLong()))
            output.write(sealed)
            if (last) break
            index++
        }
        output.flush()
    }

    fun decrypt(input: InputStream, output: OutputStream, key: ByteArray) {
        if (key.size != KEY_SIZE) throw ArchiveException.BadKey()
        val header = readUpTo(input, HEADER_SIZE)
        if (header.size != HEADER_SIZE) throw ArchiveException.Format()
        if (!header.copyOfRange(0, 8).contentEquals(MAGIC) || header[8] != VERSION) throw ArchiveException.Format()
        val prefix = header.copyOfRange(9, HEADER_SIZE)
        val src = PushbackInputStream(input, 1)
        if (!hasMore(src)) throw ArchiveException.Format() // no final chunk
        var index = 0L
        while (hasMore(src)) {
            val lenBytes = readUpTo(src, 4)
            if (lenBytes.size != 4) throw ArchiveException.Format()
            val len = ((lenBytes[0].toLong() and 0xff) shl 24) or ((lenBytes[1].toLong() and 0xff) shl 16) or
                ((lenBytes[2].toLong() and 0xff) shl 8) or (lenBytes[3].toLong() and 0xff)
            if (len < TAG_SIZE || len > CHUNK_SIZE + TAG_SIZE) throw ArchiveException.Format()
            val record = readUpTo(src, len.toInt())
            if (record.size.toLong() != len) throw ArchiveException.Format()
            val last = !hasMore(src)
            val plain = try {
                run(Cipher.DECRYPT_MODE, key, header, prefix, index, last, record)
            } catch (e: GeneralSecurityException) {
                throw ArchiveException.Authentication()
            }
            output.write(plain)
            index++
        }
        output.flush()
    }

    private fun run(mode: Int, key: ByteArray, header: ByteArray, prefix: ByteArray, index: Long, last: Boolean, data: ByteArray): ByteArray {
        if (index > 0xFFFF_FFFFL) throw ArchiveException.Format()
        val tail = u32(index) + byteArrayOf(if (last) 1 else 0)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, prefix + tail))
        c.updateAAD(header + tail)
        return c.doFinal(data)
    }

    private fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun hasMore(s: PushbackInputStream): Boolean {
        val b = s.read()
        if (b < 0) return false
        s.unread(b)
        return true
    }

    private fun readUpTo(s: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = s.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }
}
