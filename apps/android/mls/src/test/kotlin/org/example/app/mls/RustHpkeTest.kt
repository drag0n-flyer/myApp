package org.example.app.mls

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.app.contracts.HpkeException
import org.example.app.contracts.HpkeInfo
import org.example.app.contracts.X25519KeyPair

class RustHpkeTest {
    private val hpke = RustHpke()

    private class Vector(val info: String, val keyPair: X25519KeyPair, val root: JsonObject) {
        val cases: JsonArray get() = root["cases"]!!.jsonArray
        val negative: JsonArray get() = root["negative"]?.jsonArray ?: JsonArray(emptyList())
    }

    private fun b64(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun vector(name: String): Vector {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/protocol/test-vectors/$name").exists()) dir = dir.parentFile
        val file = File(checkNotNull(dir) { "repo root not found" }, "docs/protocol/test-vectors/$name")
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val pair = X25519KeyPair(
            hex(root["recipientPrivateKeyHex"]!!.jsonPrimitive.content),
            b64(root["recipientPublicKeyB64"]!!.jsonPrimitive.content),
        )
        return Vector(root["info"]!!.jsonPrimitive.content, pair, root)
    }

    private val files = listOf(
        "escrow.json" to HpkeInfo.ESCROW,
        "sealed-handle.json" to HpkeInfo.PUSH_HANDLE,
        "link-v1.json" to HpkeInfo.LINK,
    )

    @Test
    fun sealsAndOpensSharedVectors() {
        for ((name, info) in files) {
            val v = vector(name)
            assertEquals(info, v.info, name)
            assertTrue(v.cases.isNotEmpty(), name)
            for (case in v.cases) {
                val expected = case.jsonObject["plaintextJSON"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8)
                val sealed = b64(case.jsonObject["sealedB64"]!!.jsonPrimitive.content)
                assertContentEquals(expected, hpke.open(v.keyPair, v.info, sealed), name)
                // A fresh seal (new ephemeral key) opens with the vector's key.
                val fresh = hpke.seal(v.keyPair.publicKey, v.info, expected)
                assertContentEquals(expected, hpke.open(v.keyPair, v.info, fresh), name)
            }
        }
    }

    @Test
    fun infoMismatchThrows() {
        val v = vector("escrow.json")
        val sealed = b64(v.cases[0].jsonObject["sealedB64"]!!.jsonPrimitive.content)
        assertFailsWith<HpkeException> { hpke.open(v.keyPair, HpkeInfo.PUSH_HANDLE, sealed) }
    }

    @Test
    fun negativeVectorsThrow() {
        for ((name, _) in files) {
            val v = vector(name)
            for (case in v.negative) {
                val o = case.jsonObject
                val label = "$name: ${o["name"]!!.jsonPrimitive.content}"
                assertFailsWith<HpkeException>(label) {
                    hpke.open(v.keyPair, o["info"]!!.jsonPrimitive.content, b64(o["sealedB64"]!!.jsonPrimitive.content))
                }
            }
        }
    }

    @Test
    fun lowOrderRecipientKeysAreRejected() {
        for ((name, _) in listOf("escrow.json" to 0, "sealed-handle.json" to 0)) {
            val keys = vector(name).root["lowOrderRecipientKeysHex"]!!.jsonArray
            assertEquals(14, keys.size, name)
            for (k in keys) {
                assertFailsWith<HpkeException>("$name ${k.jsonPrimitive.content}") {
                    hpke.seal(hex(k.jsonPrimitive.content), HpkeInfo.ESCROW, byteArrayOf(1, 2, 3))
                }
            }
        }
    }

    @Test
    fun generatedKeyPairsRoundTrip() {
        val a = hpke.generateKeyPair()
        val b = hpke.generateKeyPair()
        assertEquals(32, a.privateKey.size)
        assertEquals(32, a.publicKey.size)
        assertTrue(!a.publicKey.contentEquals(b.publicKey))
        val plaintext = "hello".toByteArray()
        assertContentEquals(plaintext, hpke.open(a, HpkeInfo.LINK, hpke.seal(a.publicKey, HpkeInfo.LINK, plaintext)))
        assertFailsWith<HpkeException> { hpke.open(b, HpkeInfo.LINK, hpke.seal(a.publicKey, HpkeInfo.LINK, plaintext)) }
    }
}
