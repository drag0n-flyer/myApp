package org.example.app.net

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/** JSON coding for the server API. Keys are explicit in each type (`@SerialName`). */
object ServerJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}

/** RFC 3339 on the wire; accepts 0 to 9 fractional digits (Go writes nanoseconds). */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    fun parse(s: String): Instant = try {
        OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    } catch (e: DateTimeParseException) {
        throw SerializationException("invalid RFC 3339 date", e)
    }

    override fun deserialize(decoder: Decoder): Instant = parse(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value))
    }
}

/** A `ByteArray` field that is base64url without padding on the wire. */
object Base64UrlSerializer : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("Base64Url", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): ByteArray =
        Base64Url.decodeOrNull(decoder.decodeString()) ?: throw SerializationException("invalid base64url")
    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64Url.encode(value))
}
