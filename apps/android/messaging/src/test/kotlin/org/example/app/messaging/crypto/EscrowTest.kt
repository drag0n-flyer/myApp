package org.example.app.messaging.crypto

import java.io.File
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.app.contracts.HpkeException
import org.example.app.contracts.HpkeInfo
import org.example.app.contracts.X25519KeyPair
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.sync.SyncException
import org.example.app.messaging.testing.SoftwareHpke

class EscrowTest {
    private val vectors = Json.parseToJsonElement(File("../../../docs/protocol/test-vectors/escrow.json").readText()).jsonObject
    private val publicKey = Base64.getUrlDecoder().decode(vectors["recipientPublicKeyB64"]!!.jsonPrimitive.content)
    private val keyPair = X25519KeyPair(
        vectors["recipientPrivateKeyHex"]!!.jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), publicKey,
    )
    private val hpke = SoftwareHpke()

    @Test
    fun softwareHpkeOpensTheSharedVectors() {
        for (case in vectors["cases"]!!.jsonArray) {
            val sealed = Base64.getUrlDecoder().decode(case.jsonObject["sealedB64"]!!.jsonPrimitive.content)
            assertEquals(case.jsonObject["plaintextJSON"]!!.jsonPrimitive.content, hpke.open(keyPair, HpkeInfo.ESCROW, sealed).decodeToString())
        }
        for (case in vectors["negative"]!!.jsonArray) {
            val sealed = Base64.getUrlDecoder().decode(case.jsonObject["sealedB64"]!!.jsonPrimitive.content)
            assertFailsWith<HpkeException> { hpke.open(keyPair, case.jsonObject["info"]!!.jsonPrimitive.content, sealed) }
        }
    }

    @Test
    fun escrowOpensWithVectorKey() {
        val body = ContentEnvelope.Text("b0000000-0000-4000-8000-000000000001", 1793093400000, "Lunch?")
        val sealed = Escrow.seal(body, "c0000000-0000-4000-8000-000000000001", "a0000000-0000-4000-8000-000000000001", 1793093400000, publicKey, hpke)
        // enc (32) || ct, so 32 + plaintext + 16.
        val plain = hpke.open(keyPair, HpkeInfo.ESCROW, sealed)
        assertEquals(32 + plain.size + 16, sealed.size)
        val root = Json.parseToJsonElement(plain.decodeToString()).jsonObject
        assertEquals(setOf("conversationID", "senderAccountID", "sentAt", "body"), root.keys)
        assertEquals("c0000000-0000-4000-8000-000000000001", root["conversationID"]!!.jsonPrimitive.content)
        assertEquals("a0000000-0000-4000-8000-000000000001", root["senderAccountID"]!!.jsonPrimitive.content)
        assertEquals(Instant.parse("2026-10-27T09:30:00Z"), Instant.parse(root["sentAt"]!!.jsonPrimitive.content))
        // The body is the same MessageBody the MLS message carries.
        assertEquals(ContentCodec.encode(body).decodeToString(), root["body"].toString())
        // A fresh seal uses a fresh ephemeral key.
        assertNotEquals(
            sealed.toList(),
            Escrow.seal(body, "c0000000-0000-4000-8000-000000000001", "a0000000-0000-4000-8000-000000000001", 1793093400000, publicKey, hpke).toList(),
        )
    }

    @Test
    fun rfc3339UsesLatinDigitsInEveryLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "fa-IR", "mr-IN", "hi-IN", "th-TH-u-nu-thai")) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
                assertEquals("2023-11-14T22:13:20.000Z", Escrow.rfc3339(1_700_000_000_000), tag)
                val s = Escrow.rfc3339(1_709_251_199_999)
                assertTrue(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z").matches(s), "$tag: $s")
            }
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun rfc3339InUtcWithMilliseconds() {
        assertEquals("2023-11-14T22:13:20.000Z", Escrow.rfc3339(1_700_000_000_000))
        assertEquals("1970-01-01T00:00:00.007Z", Escrow.rfc3339(7))
        assertEquals("2024-02-29T23:59:59.999Z", Escrow.rfc3339(1_709_251_199_999))
        assertEquals("1969-12-31T23:59:59.999Z", Escrow.rfc3339(-1))
    }

    @Test
    fun badKeyFailsClosed() {
        val body = ContentEnvelope.Text("m", 1, "x")
        assertFailsWith<EscrowException.BadKey> { Escrow.seal(body, "c", "a", 1, ByteArray(31), hpke) }
    }

    @Test
    fun keySourceCachesAndRefetchesAfterInvalidate() = runTest {
        var fetches = 0
        val source = EscrowKeySource { fetches += 1; publicKey }
        assertContentEquals(publicKey, source.key())
        source.key()
        assertEquals(1, fetches)
        source.invalidate()
        source.key()
        assertEquals(2, fetches)
        assertFailsWith<SyncException.EscrowKeyUnavailable> { EscrowKeySource { null }.key() }
    }
}
