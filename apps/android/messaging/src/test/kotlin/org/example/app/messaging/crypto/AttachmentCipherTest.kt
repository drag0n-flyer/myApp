package org.example.app.messaging.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AttachmentCipherTest {
    private val vectors = File("../../../docs/protocol/test-vectors")

    private fun plain(n: Int) = ByteArray(n) { (it % 251).toByte() }
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun enc(data: ByteArray, key: ByteArray = ByteArray(32) { it.toByte() }): Pair<ByteArray, EncryptedBlob> {
        val out = ByteArrayOutputStream()
        val blob = AttachmentCipher.encryptWithKey(ByteArrayInputStream(data), out, key)
        return out.toByteArray() to blob
    }

    @Test fun matchesAttachmentVector() {
        val root = Json.parseToJsonElement(File(vectors, "attachment-stream.json").readText()).jsonObject
        assertEquals(65536, root["chunkSize"]!!.jsonPrimitive.int)
        val cases = root["cases"]!!.jsonArray
        assertEquals(4, cases.size)
        for (c in cases) {
            val o: JsonObject = c.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            val key = hex(o["keyHex"]!!.jsonPrimitive.content)
            val len = o["plaintextLength"]!!.jsonPrimitive.int
            val (cipher, blob) = enc(plain(len), key)
            assertEquals(o["ciphertextSha256Hex"]!!.jsonPrimitive.content, sha(cipher).joinToString("") { "%02x".format(it) }, name)
            assertEquals(o["digestB64"]!!.jsonPrimitive.content, Base64.getEncoder().encodeToString(blob.digest), name)
            assertEquals(len.toLong(), blob.size, name)
            assertEquals(cipher.size.toLong(), blob.cipherSize, name)
            assertEquals(len + 16L * maxOf(1, (len + 65535) / 65536), cipher.size.toLong(), name)
            // And it opens again.
            val f = File.createTempFile("att", ".enc")
            try {
                f.writeBytes(cipher)
                val out = ByteArrayOutputStream()
                AttachmentCipher.decrypt(f, out, key, blob.digest)
                assertContentEquals(plain(len), out.toByteArray(), name)
            } finally { f.delete() }
        }
    }

    @Test fun nonceIsFourZeroBytesThenBigEndianIndex() {
        assertContentEquals(ByteArray(12), AttachmentCipher.nonce(0))
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2), AttachmentCipher.nonce(0x102))
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0), AttachmentCipher.nonce(1L shl 56))
    }

    private fun decryptBytes(cipher: ByteArray, key: ByteArray, digest: ByteArray): ByteArray {
        val f = File.createTempFile("att", ".enc")
        try {
            f.writeBytes(cipher)
            val out = ByteArrayOutputStream()
            AttachmentCipher.decrypt(f, out, key, digest)
            return out.toByteArray()
        } finally { f.delete() }
    }

    @Test fun truncatedFileFails() {
        val key = ByteArray(32) { 7 }
        val (cipher, _) = enc(plain(3 * 65536 + 5), key)
        // Cut at a chunk boundary: every remaining chunk is valid on its own, but the last one lacks the flag.
        val cut = cipher.copyOf(2 * AttachmentCipher.SEALED_CHUNK_SIZE)
        assertFailsWith<AttachmentException.Authentication> { decryptBytes(cut, key, sha(cut)) }
        // Cut inside a chunk.
        val cut2 = cipher.copyOf(cipher.size - 3)
        assertFailsWith<AttachmentException.Authentication> { decryptBytes(cut2, key, sha(cut2)) }
        // Cut to nothing, or to less than a tag.
        assertFailsWith<AttachmentException.Format> { decryptBytes(ByteArray(0), key, sha(ByteArray(0))) }
        assertFailsWith<AttachmentException.Format> { decryptBytes(cipher.copyOf(10), key, sha(cipher.copyOf(10))) }
    }

    @Test fun reorderedChunksFail() {
        val key = ByteArray(32) { 9 }
        val (cipher, _) = enc(plain(3 * 65536 + 5), key)
        val sz = AttachmentCipher.SEALED_CHUNK_SIZE
        val swapped = cipher.copyOfRange(sz, 2 * sz) + cipher.copyOfRange(0, sz) + cipher.copyOfRange(2 * sz, cipher.size)
        assertFailsWith<AttachmentException.Authentication> { decryptBytes(swapped, key, sha(swapped)) }
    }

    @Test fun trailingChunkAppendedFails() {
        val key = ByteArray(32) { 3 }
        val (cipher, _) = enc(plain(100), key)
        val longer = cipher + ByteArray(16)
        assertFailsWith<AttachmentException.Authentication> { decryptBytes(longer, key, sha(longer)) }
    }

    @Test fun tamperedByteFailsAndWrongDigestWritesNothing() {
        val key = ByteArray(32) { 5 }
        val (cipher, blob) = enc(plain(70000), key)
        val bad = cipher.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        assertFailsWith<AttachmentException.Authentication> { decryptBytes(bad, key, sha(bad)) }
        // Stale digest: rejected before any plaintext is written.
        val f = File.createTempFile("att", ".enc")
        try {
            f.writeBytes(bad)
            val out = ByteArrayOutputStream()
            assertFailsWith<AttachmentException.Digest> { AttachmentCipher.decrypt(f, out, key, blob.digest) }
            assertEquals(0, out.size())
        } finally { f.delete() }
        assertFailsWith<AttachmentException.Authentication> { decryptBytes(cipher, ByteArray(32), blob.digest) }
        assertFailsWith<AttachmentException.BadKey> { decryptBytes(cipher, ByteArray(16), blob.digest) }
    }

    @Test fun failedFileDecryptLeavesNoPlaintext() {
        val dir = File.createTempFile("att", "").also { it.delete(); it.mkdirs() }
        try {
            val key = ByteArray(32) { 5 }
            val (cipher, _) = enc(plain(3 * 65536), key)
            val bad = cipher.copyOf().also { it[2 * AttachmentCipher.SEALED_CHUNK_SIZE + 1] = 0 }   // third chunk fails
            val src = File(dir, "in.enc").also { it.writeBytes(bad) }
            val to = File(dir, "out.bin")
            assertFailsWith<AttachmentException.Authentication> { AttachmentCipher.decrypt(src, to, key, sha(bad)) }
            assertFalse(to.exists())
            assertEquals(listOf("in.enc"), dir.list()!!.toList())
        } finally { dir.deleteRecursively() }
    }

    @Test fun resumesFromRange() {
        val key = ByteArray(32) { 11 }
        val data = plain(3 * 65536 + 123)
        val (cipher, _) = enc(data, key)
        // A ranged fetch starts at the first byte of chunk 1.
        val tail = cipher.copyOfRange(AttachmentCipher.SEALED_CHUNK_SIZE, cipher.size)
        val out = ByteArrayOutputStream()
        AttachmentCipher.decryptChunks(ByteArrayInputStream(tail), out, key, firstIndex = 1)
        assertContentEquals(data.copyOfRange(65536, data.size), out.toByteArray())
        // Starting at the wrong index fails.
        assertFailsWith<AttachmentException.Authentication> {
            AttachmentCipher.decryptChunks(ByteArrayInputStream(tail), ByteArrayOutputStream(), key, firstIndex = 0)
        }
    }

    @Test fun freshKeysDiffer() {
        assertFalse(AttachmentCipher.newKey().contentEquals(AttachmentCipher.newKey()))
        assertEquals(32, AttachmentCipher.newKey().size)
    }

    @Test fun encryptGeneratesItsOwnKeyEachTime() {
        val a = ByteArrayOutputStream()
        val b = ByteArrayOutputStream()
        val ba = AttachmentCipher.encrypt(ByteArrayInputStream(plain(10)), a)
        val bb = AttachmentCipher.encrypt(ByteArrayInputStream(plain(10)), b)
        assertEquals(32, ba.key.size)
        assertFalse(ba.key.contentEquals(bb.key))
        assertFalse(a.toByteArray().contentEquals(b.toByteArray()))
    }

    @Test fun negativeFirstIndexIsRejected() {
        val (cipher, _) = enc(plain(10))
        assertFailsWith<IllegalArgumentException> {
            AttachmentCipher.decryptChunks(ByteArrayInputStream(cipher), ByteArrayOutputStream(), ByteArray(32) { it.toByte() }, firstIndex = -1)
        }
    }

    @Test fun decryptTempNamesAreUnique() {
        val dir = File.createTempFile("att", "").also { it.delete(); it.mkdirs() }
        try {
            val key = ByteArray(32) { 5 }
            val (cipher, _) = enc(plain(3 * 65536), key)
            val src = File(dir, "in.enc").also { it.writeBytes(cipher) }
            val to = File(dir, "out.bin")
            // A stale file with the old fixed temp name must neither be used nor disturbed.
            val stale = File(dir, "out.bin.part").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            AttachmentCipher.decrypt(src, to, key, sha(cipher))
            assertEquals(3 * 65536, to.length().toInt())
            assertContentEquals(byteArrayOf(1, 2, 3), stale.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun sealedSizeMatchesFraming() {
        assertEquals(16L, AttachmentCipher.sealedSize(0))
        assertEquals(65_536L + 16, AttachmentCipher.sealedSize(65_536))
        assertEquals(65_537L + 32, AttachmentCipher.sealedSize(65_537))
        val (cipher, blob) = enc(plain(150_000))
        assertEquals(cipher.size.toLong(), AttachmentCipher.sealedSize(blob.size))
    }
}
