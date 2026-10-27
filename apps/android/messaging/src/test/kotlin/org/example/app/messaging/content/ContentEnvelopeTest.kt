package org.example.app.messaging.content

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.app.contracts.DataMode

private val vectors = File("../../../docs/protocol/test-vectors")

class ContentEnvelopeTest {
    private fun cases(): List<Pair<String, String>> =
        (Json.parseToJsonElement(File(vectors, "content-v1.json").readText()) as JsonArray).map {
            it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["json"]!!.jsonPrimitive.content
        }

    @Test
    fun decodesEverySwiftContentFixture() {
        val all = cases()
        assertEquals(15, all.size)
        for ((name, text) in all) {
            val env = ContentCodec.decode(text.encodeToByteArray())
            assertTrue(env !is ContentEnvelope.Unknown, "$name decoded as Unknown")
            val again = ContentCodec.encode(env).decodeToString()
            assertEquals(Json.parseToJsonElement(text), Json.parseToJsonElement(again), name)
            assertEquals(text, again, "$name bytes")
        }
    }

    @Test
    fun encodesCallsigLikeSwift() {
        val text = cases().first { it.first == "callsig" }.second
        val env = assertIs<ContentEnvelope.CallSig>(ContentCodec.decode(text.encodeToByteArray()))
        assertEquals(DataMode.Balanced, env.p.ceiling)
        val p = Json.parseToJsonElement(ContentCodec.encode(env).decodeToString()).jsonObject["p"]!!.jsonObject
        assertEquals("balanced", p["ceiling"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownKindIsUnknown() {
        val env = ContentCodec.decode("""{"at":1,"id":"x","k":"sticker","v":1}""".encodeToByteArray())
        assertEquals("sticker", assertIs<ContentEnvelope.Unknown>(env).kind)
        assertEquals("invalid", assertIs<ContentEnvelope.Unknown>(ContentCodec.decode("nope".encodeToByteArray())).kind)
        assertEquals("invalid", assertIs<ContentEnvelope.Unknown>(ContentCodec.decode("""{"at":1,"id":"x","k":"text","v":1}""".encodeToByteArray())).kind)
        assertEquals("mode", assertIs<ContentEnvelope.Unknown>(ContentCodec.decode("""{"at":1,"id":"x","k":"mode","m":"zzz","v":1}""".encodeToByteArray())).kind)
    }

    @Test
    fun v2IsUnknown() {
        val env = ContentCodec.decode("""{"at":1,"id":"x","k":"text","t":"hi","v":2}""".encodeToByteArray())
        assertEquals("text", assertIs<ContentEnvelope.Unknown>(env).kind)
    }

    @Test
    fun textOver4096Rejected() {
        ContentCodec.encode(ContentEnvelope.Text("i", 1, "a".repeat(4096)))
        assertFailsWith<IllegalArgumentException> { ContentCodec.encode(ContentEnvelope.Text("i", 1, "a".repeat(4097))) }
        assertFailsWith<IllegalArgumentException> { ContentCodec.encode(ContentEnvelope.Edit("i", 1, "t", "a".repeat(4097))) }
    }

    @Test
    fun linkSnapshotDecodesSwiftShape() {
        val root = Json.parseToJsonElement(File(vectors, "link-v1.json").readText()).jsonObject
        val text = (root["cases"] as JsonArray)[0].jsonObject["plaintextJSON"]!!.jsonPrimitive.content
        val snap = LinkSnapshot.decode(text.encodeToByteArray())
        assertEquals(1, snap.v)
        assertEquals(2, snap.contacts.size)
        assertEquals("Bob Ünal", snap.contacts[0].displayName)
        assertEquals(1793093400000L, snap.contacts[0].addedAt)
        assertEquals(true, snap.chats[0].muted)
        assertEquals(listOf("c0000000-0000-4000-8000-000000000001"), snap.folders[0].chatIDs)
        assertEquals(listOf("a0000000-0000-4000-8000-000000000009"), snap.blocked)
        assertEquals("false", snap.settings["setting.readReceipts"]!!.decodeToString())
        assertEquals(Json.parseToJsonElement(text), Json.parseToJsonElement(snap.encode().decodeToString()))
    }
}
