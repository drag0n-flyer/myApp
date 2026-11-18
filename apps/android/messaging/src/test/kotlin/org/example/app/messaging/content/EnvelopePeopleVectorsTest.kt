package org.example.app.messaging.content

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The vectors shared with the Apple client and the server: server/internal/vectors/envelope_people.json. */
class EnvelopePeopleVectorsTest {
    private fun cases(): List<JsonObject> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "server/internal/vectors/envelope_people.json").exists()) dir = dir.parentFile
        val file = File(requireNotNull(dir) { "repository root not found" }, "server/internal/vectors/envelope_people.json")
        return Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull

    @Test fun matchesEverySharedVector() {
        val all = cases()
        assertTrue(all.size >= 60, "vectors file looks short: ${all.size}")
        for (case in all) {
            val name = case.str("name")!!
            val expect = case.getValue("expect").jsonObject
            val env = ContentCodec.decode(case.str("json")!!.encodeToByteArray())
            val unsupported = expect.str("unsupported")
            if (unsupported != null) {
                assertEquals(unsupported, assertIs<ContentEnvelope.Unknown>(env, name).kind, name)
                continue
            }
            when (expect.str("kind")) {
                "typing" -> assertEquals(expect["on"]!!.jsonPrimitive.booleanOrNull, assertIs<ContentEnvelope.Typing>(env, name).on, name)
                "presence" -> assertEquals(expect.str("state"), assertIs<ContentEnvelope.Presence>(env, name).state, name)
                "timer" -> assertEquals(expect.int("seconds"), assertIs<ContentEnvelope.Timer>(env, name).seconds, name)
                "profile" -> {
                    val p = assertIs<ContentEnvelope.Profile>(env, name)
                    assertEquals(expect.str("name"), p.name, name)
                    assertEquals(expect.int("ver"), p.ver, name)
                    assertEquals(expect.int("photoLen"), p.photo?.size, name)
                }
                "text" -> {
                    val t = assertIs<ContentEnvelope.Text>(env, name)
                    assertEquals(expect.str("text"), t.t, name)
                    assertEquals(expect.int("ttl"), t.ttl, name)
                    val lp = expect["lp"]?.jsonObject
                    if (lp == null) assertNull(t.lp, name) else {
                        val got = t.lp!!
                        assertEquals(lp.str("url"), got.url, name)
                        assertEquals(lp.str("title"), got.title, name)
                        assertEquals(lp.str("desc"), got.desc, name)
                        assertEquals(lp.int("imgLen"), got.img?.size, name)
                    }
                }
                "photo" -> {
                    val p = assertIs<ContentEnvelope.Photo>(env, name)
                    assertEquals(expect.str("attId"), p.a.id, name)
                    assertEquals(expect.int("w"), p.w, name); assertEquals(expect.int("h"), p.h, name)
                    caption(expect, p.c, name); assertEquals(expect.int("ttl"), p.ttl, name)
                }
                "voice" -> {
                    val v = assertIs<ContentEnvelope.Voice>(env, name)
                    assertEquals(expect.str("attId"), v.a.id, name)
                    assertEquals(expect["duration"]!!.jsonPrimitive.doubleOrNull, v.dur, name)
                    assertEquals(expect.int("ttl"), v.ttl, name)
                }
                "file" -> {
                    val f = assertIs<ContentEnvelope.File>(env, name)
                    assertEquals(expect.str("attId"), f.a.id, name)
                    assertEquals(expect.str("name"), f.name, name); assertEquals(expect.str("mime"), f.mime, name)
                    caption(expect, f.c, name); assertEquals(expect.int("ttl"), f.ttl, name)
                }
                else -> error("$name: unknown expectation $expect")
            }
        }
    }

    private fun caption(expect: JsonObject, got: String?, name: String) {
        val len = expect.int("cLen")
        if (len == null) {
            assertEquals(expect.str("c"), got, name)
            assertNull(expect.int("cScalars"), name)
            return
        }
        assertEquals(len, graphemes(got!!), name)
        expect.int("cScalars")?.let { assertEquals(it, got.codePointCount(0, got.length), name) }
    }

    /** Exactly [n] graphemes when keeping n whole changes nothing and keeping one fewer does. */
    private fun graphemes(s: String): Int {
        for (n in 1..TextLimit.MAX_GRAPHEMES) if (TextLimit.truncate(s, n) == s) return n
        return TextLimit.MAX_GRAPHEMES + 1
    }

    @Test fun peopleEnvelopesRoundTripThroughTheEncoder() {
        val sample = listOf(
            ContentEnvelope.Typing("i", 1, true),
            ContentEnvelope.Presence("i", 1, "away"),
            ContentEnvelope.Timer("i", 1, 604_800),
            ContentEnvelope.Profile("i", 1, "Ann", org.example.app.messaging.TestJpeg.of(), 3),
            ContentEnvelope.Text("i", 1, "see https://a.example/x", ttl = 60, lp = LinkPreview("https://a.example/x", "T", "D", byteArrayOf(-1, -40, 2))),
        )
        for (env in sample) {
            val bytes = ContentCodec.encode(env)
            val again = ContentCodec.decode(bytes)
            assertEquals(String(bytes), String(ContentCodec.encode(again)))
            assertEquals(env::class, again::class)
        }
    }

    @Test fun encodesWireNamesExactly() {
        assertEquals("""{"at":1,"id":"i","k":"typing","on":true,"v":1}""", String(ContentCodec.encode(ContentEnvelope.Typing("i", 1, true))))
        assertEquals("""{"at":1,"id":"i","k":"timer","seconds":86400,"v":1}""", String(ContentCodec.encode(ContentEnvelope.Timer("i", 1, 86_400))))
        assertEquals("""{"at":1,"id":"i","k":"text","t":"x","ttl":5,"v":1}""", String(ContentCodec.encode(ContentEnvelope.Text("i", 1, "x", ttl = 5))))
        assertEquals(
            "AQ==", Base64.getEncoder().encodeToString(byteArrayOf(1)), // the encoder uses standard base64, as `th` does
        )
    }

    @Test fun previewSchemeIsCaseInsensitiveButStillHttpsOnly() {
        fun lp(url: String) = ContentCodec.decode(
            """{"v":1,"id":"m","at":1,"k":"text","t":"see $url","lp":{"url":"$url","title":"T"}}""".encodeToByteArray(),
        ) as ContentEnvelope.Text
        assertEquals("HTTPS://Example.com/x", lp("HTTPS://Example.com/x").lp?.url)
        assertNull(lp("http://example.com/x").lp)
        assertNull(lp("HTTP://example.com/x").lp)
    }

    @Test fun photoAndPreviewImageAcceptPaddedAndUnpaddedBase64AndEncodeWithPadding() {
        val jpeg = org.example.app.messaging.TestJpeg.of(size = 16) // 16 bytes: base64 ends in "AA==" padded, "AA" unpadded
        val padded = Base64.getEncoder().encodeToString(jpeg)
        for (b64 in listOf(padded, padded.trimEnd('='))) {
            val prof = ContentCodec.decode("""{"v":1,"id":"p","at":1,"k":"profile","name":"Ann","photo":"$b64","ver":1}""".encodeToByteArray())
            assertEquals(jpeg.toList(), assertIs<ContentEnvelope.Profile>(prof).photo?.toList(), b64)
            val text = ContentCodec.decode(
                """{"v":1,"id":"m","at":1,"k":"text","t":"see https://a.example/x","lp":{"url":"https://a.example/x","title":"T","img":"$b64"}}""".encodeToByteArray(),
            )
            assertEquals(jpeg.toList(), assertIs<ContentEnvelope.Text>(text).lp?.img?.toList(), b64)
        }
        val out = String(ContentCodec.encode(ContentEnvelope.Profile("p", 1, "Ann", jpeg, 1)))
        assertTrue(out.contains("\"$padded\""), out)
    }
}
