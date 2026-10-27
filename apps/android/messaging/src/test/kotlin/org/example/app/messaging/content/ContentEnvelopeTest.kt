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
    fun codecDoesNotCapTextButHasBackstop() {
        ContentCodec.encode(ContentEnvelope.Text("i", 1, "a".repeat(5000)))
        assertFailsWith<IllegalArgumentException> { ContentCodec.encode(ContentEnvelope.Text("i", 1, "a".repeat(70_000))) }
        val big = """{"at":1,"id":"x","k":"text","t":"${"a".repeat(70_000)}","v":1}""".encodeToByteArray()
        assertEquals("invalid", assertIs<ContentEnvelope.Unknown>(ContentCodec.decode(big)).kind)
    }

    @Test
    fun unknownReEncodesLosslessly() {
        val raw = """{"at":1,"future":{"x":[1,2]},"id":"x","k":"sticker","v":1}"""
        assertEquals(raw, ContentCodec.encode(ContentCodec.decode(raw.encodeToByteArray())).decodeToString())
    }

    @Test
    fun wholeNumberDurationMatchesSwift() {
        // Swift JSONEncoder writes Double 3.0 as `3` (checked with swiftc on Linux).
        val att = Att("a", ByteArray(1), ByteArray(1), 1, 1)
        val out = ContentCodec.encode(ContentEnvelope.Voice("i", 1, att, 3.0)).decodeToString()
        assertTrue(out.contains(""""dur":3,"""), out)
        val back = assertIs<ContentEnvelope.Voice>(ContentCodec.decode(out.encodeToByteArray()))
        assertEquals(3.0, back.dur)
        assertEquals(out, ContentCodec.encode(back).decodeToString())
        assertTrue(ContentCodec.encode(ContentEnvelope.Voice("i", 1, att, 14.5)).decodeToString().contains(""""dur":14.5"""))
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
