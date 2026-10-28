package org.example.app.mls

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.app.contracts.HpkeInfo
import org.example.app.contracts.X25519KeyPair
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.crypto.Escrow

/** `Escrow.seal` with the Rust HPKE (the production path), opened with the shared escrow vector's private key. */
class EscrowRustTest {
    @Test
    fun escrowSealedWithRustHpkeOpensWithTheVectorKey() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/protocol/test-vectors/escrow.json").exists()) dir = dir.parentFile
        val vectors = Json.parseToJsonElement(File(checkNotNull(dir), "docs/protocol/test-vectors/escrow.json").readText()).jsonObject
        val publicKey = Base64.getUrlDecoder().decode(vectors["recipientPublicKeyB64"]!!.jsonPrimitive.content)
        val privateKey = vectors["recipientPrivateKeyHex"]!!.jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val hpke = RustHpke()

        val body = ContentEnvelope.Text("b0000000-0000-4000-8000-000000000001", 1793093400000, "Lunch?")
        val sealed = Escrow.seal(body, "c0000000-0000-4000-8000-000000000001", "a0000000-0000-4000-8000-000000000001", 1793093400000, publicKey, hpke)
        val plain = hpke.open(X25519KeyPair(privateKey, publicKey), HpkeInfo.ESCROW, sealed).decodeToString()
        val root = Json.parseToJsonElement(plain).jsonObject
        assertEquals(setOf("conversationID", "senderAccountID", "sentAt", "body"), root.keys)
        assertEquals("2026-10-27T09:30:00.000Z", root["sentAt"]!!.jsonPrimitive.content)
        assertTrue(root["body"]!!.jsonObject["t"]!!.jsonPrimitive.content == "Lunch?")
    }
}
