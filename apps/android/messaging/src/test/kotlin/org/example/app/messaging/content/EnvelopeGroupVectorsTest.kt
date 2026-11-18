package org.example.app.messaging.content

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The `group` envelope vectors shared with the Apple client: server/internal/vectors/envelope_groups.json. */
class EnvelopeGroupVectorsTest {
    private fun cases(): List<JsonObject> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "server/internal/vectors/envelope_groups.json").exists()) dir = dir.parentFile
        val file = File(requireNotNull(dir) { "repository root not found" }, "server/internal/vectors/envelope_groups.json")
        return Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull

    @Test fun matchesEverySharedVector() {
        val all = cases()
        assertTrue(all.size >= 25, "vectors file looks short: ${all.size}")
        for (case in all) {
            val name = case.str("name")!!
            val expect = case.getValue("expect").jsonObject
            val env = ContentCodec.decode(case.str("json")!!.encodeToByteArray())
            val unsupported = expect.str("unsupported")
            if (unsupported != null) {
                assertEquals(unsupported, assertIs<ContentEnvelope.Unknown>(env, name).kind, name)
                continue
            }
            assertEquals("group", expect.str("kind"), name)
            val g = assertIs<ContentEnvelope.Group>(env, name)
            assertEquals(expect.str("name"), g.name, name)
            assertEquals(expect.int("ver"), g.ver, name)
            assertEquals(expect.int("photoLen"), g.photo?.size, name)
        }
    }

    @Test fun groupRoundTripsThroughTheEncoderWithWireNamesExactly() {
        assertEquals(
            """{"at":1,"id":"i","k":"group","name":"Book club","v":1,"ver":2}""",
            String(ContentCodec.encode(ContentEnvelope.Group("i", 1, "Book club", null, 2))),
        )
        val withPhoto = ContentEnvelope.Group("i", 1, "Café", byteArrayOf(-1, -40, 1, 2), 7)
        val bytes = ContentCodec.encode(withPhoto)
        assertEquals(withPhoto, ContentCodec.decode(bytes))
        assertEquals(String(bytes), String(ContentCodec.encode(ContentCodec.decode(bytes))))
    }

    @Test fun aNameAndAVersionMustSurviveTheSameRulesOnSendAsOnReceive() {
        // The encoder writes what it is given; the decoder is the gate. A bad one decodes to Unknown("group").
        val bad = ContentCodec.encode(ContentEnvelope.Group("i", 1, "", null, 1))
        assertEquals("group", assertIs<ContentEnvelope.Unknown>(ContentCodec.decode(bad)).kind)
        val zero = ContentCodec.encode(ContentEnvelope.Group("i", 1, "Ok", null, 0))
        assertEquals("group", assertIs<ContentEnvelope.Unknown>(ContentCodec.decode(zero)).kind)
    }

    @Test fun aGroupPhotoOfThreeBytesIsInvalidAndOfFourIsKept() {
        fun json(bytes: ByteArray) = """{"k":"group","id":"i","at":1,"v":1,"name":"Ok","ver":1,"photo":"${java.util.Base64.getEncoder().encodeToString(bytes)}"}""".encodeToByteArray()
        val three = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        assertEquals("group", assertIs<ContentEnvelope.Unknown>(ContentCodec.decode(json(three))).kind)
        assertEquals(4, assertIs<ContentEnvelope.Group>(ContentCodec.decode(json(three + byteArrayOf(0)))).photo?.size)
    }
}
