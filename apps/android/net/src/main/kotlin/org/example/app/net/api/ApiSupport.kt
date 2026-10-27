package org.example.app.net.api

import java.net.URLEncoder
import kotlinx.serialization.SerializationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import org.example.app.contracts.ServerException
import org.example.app.net.ServerJson

internal inline fun <reified T> encodeBody(value: T): JsonElement =
    ServerJson.json.encodeToJsonElement(serializer<T>(), value)

/** One URL path segment, percent-encoded. */
internal fun seg(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

internal fun <T> decodeBody(bytes: ByteArray, s: KSerializer<T>): T = try {
    ServerJson.json.decodeFromString(s, bytes.decodeToString())
} catch (e: SerializationException) {
    throw ServerException(0, "decoding", null, null)
} catch (e: IllegalArgumentException) {
    throw ServerException(0, "decoding", null, null)
}

internal val emptyObject = JsonObject(emptyMap())
