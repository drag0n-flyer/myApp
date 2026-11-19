package org.example.app.core.archive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** docs/protocol/test-vectors/archive-v1.json (made by `go run ./archive` in tools/vectors) and the format rules. */
class ArchiveCipherTest {
    private val root = Json.parseToJsonElement(File("../../../docs/protocol/test-vectors/archive-v1.json").readText()).jsonObject

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun plain(n: Int) = ByteArray(n) { (it % 251).toByte() }
    private val key = hex(root["keyHex"]!!.jsonPrimitive.content)
    private val prefix = hex(root["noncePrefixHex"]!!.jsonPrimitive.content)

    private fun seal(p: ByteArray, k: ByteArray = key, pre: ByteArray = prefix): ByteArray {
        val out = ByteArrayOutputStream()
        ArchiveCipher.encryptWithPrefix(ByteArrayInputStream(p), out, k, pre)
        return out.toByteArray()
    }

    private fun open(c: ByteArray, k: ByteArray = key): ByteArray {
        val out = ByteArrayOutputStream()
        ArchiveCipher.decrypt(ByteArrayInputStream(c), out, k)
        return out.toByteArray()
    }

    private fun records(c: ByteArray): List<ByteArray> {
        val r = mutableListOf<ByteArray>()
        var pos = 16
        while (pos < c.size) {
            val n = ((c[pos].toInt() and 0xff) shl 24) or ((c[pos + 1].toInt() and 0xff) shl 16) or
                ((c[pos + 2].toInt() and 0xff) shl 8) or (c[pos + 3].toInt() and 0xff)
            r += c.copyOfRange(pos, pos + 4 + n)
            pos += 4 + n
        }
        return r
    }

    @Test fun matchesVectorsAndNegativeCases() {
        assertEquals(1 shl 20, root["chunkSize"]!!.jsonPrimitive.int)
        val cts = mutableMapOf<String, ByteArray>()
        val cases = root["cases"]!!.jsonArray
        assertEquals(4, cases.size)
        for (c in cases) {
            val o = c.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            val len = o["plaintextLength"]!!.jsonPrimitive.int
            val ct = seal(plain(len))
            assertEquals(o["ciphertextLength"]!!.jsonPrimitive.int, ct.size, name)
            assertEquals(o["ciphertextSha256Hex"]!!.jsonPrimitive.content, toHex(MessageDigest.getInstance("SHA-256").digest(ct)), name)
            o["ciphertextHex"]?.let { assertEquals(it.jsonPrimitive.content, toHex(ct), name) }
            assertContentEquals(plain(len), open(ct), name)
            cts[name] = ct
        }
        val neg = root["negative"]!!.jsonArray
        assertTrue(neg.size >= 10)
        for (n in neg) {
            val o = n.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            var data = cts[o["case"]!!.jsonPrimitive.content]!!.copyOf()
            val a = o["a"]?.jsonPrimitive?.int ?: 0
            val b = o["b"]?.jsonPrimitive?.int ?: 0
            when (val op = o["op"]!!.jsonPrimitive.content) {
                "none" -> {}
                "dropLastRecord" -> data = data.copyOf(data.size - records(data).last().size)
                "swapRecords" -> {
                    val r = records(data).toMutableList()
                    val t = r[a]; r[a] = r[b]; r[b] = t
                    data = data.copyOf(16) + r.fold(ByteArray(0)) { x, y -> x + y }
                }
                "flipBit" -> data[a] = (data[a].toInt() xor (1 shl b)).toByte()
                "appendHex" -> data += hex(o["hex"]!!.jsonPrimitive.content)
                "emptyInput" -> data = ByteArray(0)
                else -> error("unknown op $op")
            }
            val k = o["keyHex"]?.let { hex(it.jsonPrimitive.content) } ?: key
            assertFailsWith<ArchiveException>(name) { open(data, k) }
        }
    }

    @Test fun randomPrefixRoundTripAcrossChunks() {
        val k = ArchiveCipher.newKey()
        val p = plain(2 * ArchiveCipher.CHUNK_SIZE + 17)
        val out = ByteArrayOutputStream()
        ArchiveCipher.encrypt(ByteArrayInputStream(p), out, k)
        val c = out.toByteArray()
        assertEquals(3, records(c).size)
        assertContentEquals(p, open(c, k))
    }

    @Test fun exactMultipleOfChunkHasNoTrailingEmptyChunk() {
        assertEquals(1, records(seal(plain(ArchiveCipher.CHUNK_SIZE))).size)
        assertEquals(2, records(seal(plain(2 * ArchiveCipher.CHUNK_SIZE))).size)
    }

    @Test fun badKeyLength() {
        assertFailsWith<ArchiveException.BadKey> { open(seal(plain(1)), ByteArray(16)) }
    }
}
