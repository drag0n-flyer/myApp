package org.example.app.net

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CodingTest {
    @Serializable
    private class Stamp(@Serializable(with = InstantSerializer::class) val at: Instant)

    @Serializable
    private class Blob(@Serializable(with = Base64UrlSerializer::class) val b: ByteArray)

    @Test
    fun base64UrlRoundTrip() {
        val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte())
        assertEquals("-_8", Base64Url.encode(bytes))
        assertContentEquals(bytes, Base64Url.decode("-_8"))
        assertContentEquals(bytes, Base64Url.decode("-_8="))
        assertNull(Base64Url.decodeOrNull("a"))
        assertNull(Base64Url.decodeOrNull("+/8"))
        assertContentEquals(ByteArray(0), Base64Url.decode(""))
        val blob = ServerJson.json.decodeFromString(Blob.serializer(), """{"b":"-_8"}""")
        assertContentEquals(bytes, blob.b)
        assertEquals("""{"b":"-_8"}""", ServerJson.json.encodeToString(Blob.serializer(), blob))
        assertFailsWith<SerializationException> { ServerJson.json.decodeFromString(Blob.serializer(), """{"b":"a"}""") }
    }

    @Test
    fun decodesGoTimestamps() {
        fun at(s: String) = ServerJson.json.decodeFromString(Stamp.serializer(), """{"at":"$s"}""").at
        assertEquals(Instant.parse("2026-10-28T12:00:00Z"), at("2026-10-28T12:00:00Z"))
        assertEquals(Instant.parse("2026-10-28T12:00:00.123456789Z"), at("2026-10-28T12:00:00.123456789Z"))
        assertEquals(Instant.parse("2026-10-28T10:00:00.5Z"), at("2026-10-28T12:00:00.5+02:00"))
        assertFailsWith<SerializationException> { at("yesterday") }
    }
}
