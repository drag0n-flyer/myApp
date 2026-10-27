package org.example.app.messaging.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.example.app.contracts.CallSignalPayload
import java.util.Base64

/** Message payload: versioned JSON with short keys, carried as the MLS application-message plaintext. */
sealed interface ContentEnvelope {
    val v: Int
    val id: String
    val at: Long

    data class Text(override val id: String, override val at: Long, val t: String, val r: String? = null, override val v: Int = 1) : ContentEnvelope
    class Photo(
        override val id: String, override val at: Long, val a: Att, val w: Int, val h: Int,
        val th: ByteArray? = null, val r: String? = null, override val v: Int = 1,
    ) : ContentEnvelope {
        override fun equals(other: Any?) = other is Photo && id == other.id && at == other.at && a == other.a &&
            w == other.w && h == other.h && (th contentEquals other.th) && r == other.r && v == other.v
        override fun hashCode() = id.hashCode() + at.hashCode() + a.hashCode()
    }
    /** [dur] is seconds. The audio is Ogg Opus (`audio/ogg; codecs=opus`); the envelope carries no mime for it. */
    data class Voice(override val id: String, override val at: Long, val a: Att, val dur: Double, val r: String? = null, override val v: Int = 1) : ContentEnvelope
    data class File(override val id: String, override val at: Long, val a: Att, val name: String, val mime: String, val r: String? = null, override val v: Int = 1) : ContentEnvelope
    data class Edit(override val id: String, override val at: Long, val target: String, val t: String, override val v: Int = 1) : ContentEnvelope
    data class Delete(override val id: String, override val at: Long, val target: String, override val v: Int = 1) : ContentEnvelope
    data class React(override val id: String, override val at: Long, val target: String, val e: String, override val v: Int = 1) : ContentEnvelope
    data class Receipt(override val id: String, override val at: Long, val s: ReceiptKind, val ids: List<String>, override val v: Int = 1) : ContentEnvelope
    data class CallSig(override val id: String, override val at: Long, val p: CallSignalPayload, override val v: Int = 1) : ContentEnvelope
    data class Mode(override val id: String, override val at: Long, val m: String, override val v: Int = 1) : ContentEnvelope

    /** A newer version, an unknown kind or a malformed message. [kind] is `"invalid"` when not even the header reads. [raw] is the original JSON text. */
    data class Unknown(override val id: String, override val at: Long, val kind: String, val raw: String, override val v: Int = 1) : ContentEnvelope
}

enum class ReceiptKind(val wire: String) { Delivered("delivered"), Read("read") }

object ContentCodec {
    const val MAX_TEXT_LENGTH = 4096
    private val json = Json { ignoreUnknownKeys = true }
    private val knownKinds = setOf("text", "photo", "voice", "file", "edit", "delete", "react", "receipt", "callsig", "mode")
    private val invalidKinds = setOf("mode", "receipt")

    /** Sorted keys, no whitespace, like the Swift encoder. Throws [IllegalArgumentException] for text over 4096 characters. */
    fun encode(env: ContentEnvelope): ByteArray {
        val obj = when (env) {
            is ContentEnvelope.Text -> { checkLength(env.t); head(env, "text") { put("t", env.t); env.r?.let { put("r", it) } } }
            is ContentEnvelope.Photo -> head(env, "photo") {
                put("a", attJson(env.a)); put("w", env.w); put("h", env.h)
                env.th?.let { put("th", b64(it)) }; env.r?.let { put("r", it) }
            }
            is ContentEnvelope.Voice -> head(env, "voice") { put("a", attJson(env.a)); put("dur", env.dur); env.r?.let { put("r", it) } }
            is ContentEnvelope.File -> head(env, "file") {
                put("a", attJson(env.a)); put("name", env.name); put("mime", env.mime); env.r?.let { put("r", it) }
            }
            is ContentEnvelope.Edit -> { checkLength(env.t); head(env, "edit") { put("target", env.target); put("t", env.t) } }
            is ContentEnvelope.Delete -> head(env, "delete") { put("target", env.target) }
            is ContentEnvelope.React -> head(env, "react") { put("target", env.target); put("e", env.e) }
            is ContentEnvelope.Receipt -> head(env, "receipt") {
                put("s", env.s.wire); put("ids", JsonArray(env.ids.map { JsonPrimitive(it) }))
            }
            is ContentEnvelope.CallSig -> head(env, "callsig") {
                put("p", json.encodeToJsonElement(CallSignalPayload.serializer(), env.p))
            }
            is ContentEnvelope.Mode -> head(env, "mode") { put("m", env.m) }
            is ContentEnvelope.Unknown -> head(env, env.kind) {}
        }
        return sorted(obj).toString().encodeToByteArray()
    }

    /** Never throws: bad JSON, a newer version, an unknown kind or a malformed known kind all give [ContentEnvelope.Unknown]. */
    fun decode(data: ByteArray): ContentEnvelope {
        val text = data.decodeToString()
        val obj = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
        val v = obj?.get("v").int(); val id = obj?.get("id").str(); val at = obj?.get("at").long(); val k = obj?.get("k").str()
        if (obj == null || v == null || id == null || at == null || k == null) return ContentEnvelope.Unknown("", 0, "invalid", text)
        fun unknown(kind: String) = ContentEnvelope.Unknown(id, at, kind, text, v)
        if (v != 1 || k !in knownKinds) return unknown(k)
        return try { body(obj, k, id, at) } catch (_: Exception) { null } ?: unknown(if (k in invalidKinds) k else "invalid")
    }

    private fun body(o: JsonObject, k: String, id: String, at: Long): ContentEnvelope? {
        val r = o["r"].str()
        return when (k) {
            "text" -> o["t"].str()?.let { ContentEnvelope.Text(id, at, it, r) }
            "photo" -> {
                val a = att(o["a"]); val w = o["w"].int(); val h = o["h"].int()
                if (a == null || w == null || h == null) null
                else ContentEnvelope.Photo(id, at, a, w, h, o["th"].str()?.let { Base64.getDecoder().decode(it) }, r)
            }
            "voice" -> { val a = att(o["a"]); val d = o["dur"].dbl(); if (a == null || d == null) null else ContentEnvelope.Voice(id, at, a, d, r) }
            "file" -> {
                val a = att(o["a"]); val n = o["name"].str(); val m = o["mime"].str()
                if (a == null || n == null || m == null) null else ContentEnvelope.File(id, at, a, n, m, r)
            }
            "edit" -> { val t = o["target"].str(); val x = o["t"].str(); if (t == null || x == null) null else ContentEnvelope.Edit(id, at, t, x) }
            "delete" -> o["target"].str()?.let { ContentEnvelope.Delete(id, at, it) }
            "react" -> { val t = o["target"].str(); val e = o["e"].str(); if (t == null || e == null) null else ContentEnvelope.React(id, at, t, e) }
            "receipt" -> {
                val s = ReceiptKind.entries.firstOrNull { it.wire == o["s"].str() }
                val ids = (o["ids"] as? JsonArray)?.map { it.str() ?: return null }
                if (s == null || ids == null) null else ContentEnvelope.Receipt(id, at, s, ids)
            }
            "callsig" -> o["p"]?.let { ContentEnvelope.CallSig(id, at, json.decodeFromJsonElement(CallSignalPayload.serializer(), it)) }
            "mode" -> o["m"].str()?.takeIf { it == "e2ee" || it == "server_readable" }?.let { ContentEnvelope.Mode(id, at, it) }
            else -> null
        }
    }

    private fun att(e: JsonElement?): Att? {
        val o = e as? JsonObject ?: return null
        return json.decodeFromJsonElement(Att.serializer(), o)
    }

    private fun attJson(a: Att): JsonElement = json.encodeToJsonElement(Att.serializer(), a)
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private fun checkLength(t: String) =
        require(t.codePointCount(0, t.length) <= MAX_TEXT_LENGTH) { "text longer than $MAX_TEXT_LENGTH characters" }

    private inline fun head(env: ContentEnvelope, k: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        buildJsonObject { put("v", env.v); put("id", env.id); put("at", env.at); put("k", k); fields() }

    private fun sorted(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { it.key to sorted(it.value) })
        is JsonArray -> JsonArray(e.map { sorted(it) })
        else -> e
    }

    private fun JsonElement?.prim() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonElement?.str() = prim()?.takeIf { it.isString }?.contentOrNull
    private fun JsonElement?.int() = prim()?.takeIf { !it.isString }?.intOrNull
    private fun JsonElement?.long() = prim()?.takeIf { !it.isString }?.longOrNull
    private fun JsonElement?.dbl() = prim()?.takeIf { !it.isString }?.doubleOrNull
}
