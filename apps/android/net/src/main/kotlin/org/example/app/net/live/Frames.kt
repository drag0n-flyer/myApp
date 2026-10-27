package org.example.app.net.live

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.app.contracts.InboxItem
import org.example.app.contracts.ServerEvent
import org.example.app.net.Base64Url
import org.example.app.net.ServerJson
import org.example.app.net.models.InboxItemWire

internal sealed interface IncomingFrame {
    class Event(val event: ServerEvent) : IncomingFrame
    class Ack(val id: String, val data: JsonObject?) : IncomingFrame
    class Error(val id: String, val code: String) : IncomingFrame

    /** A known frame type whose fields did not decode. Only the type is ever reported, never the payload. */
    class Malformed(val type: String) : IncomingFrame
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.bytes(key: String): ByteArray? = str(key)?.let { Base64Url.decodeOrNull(it) }

/** Null for unknown frame types and text that is not a JSON object; [IncomingFrame.Malformed] for known types with bad fields. */
internal fun decodeFrame(text: String): IncomingFrame? {
    val root = try {
        ServerJson.json.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return null
    val type = root.str("type") ?: return null
    val id = root.str("id")
    val data = root["data"] as? JsonObject
    val bad = IncomingFrame.Malformed(type)
    fun event(e: ServerEvent?): IncomingFrame = e?.let { IncomingFrame.Event(it) } ?: bad
    return try {
        when (type) {
            "inbox" -> event(data?.let { d ->
                val w = ServerJson.json.decodeFromJsonElement(InboxItemWire.serializer(), d)
                ServerEvent.Inbox(InboxItem(w.cursor, w.kind, w.conversationID, w.seq, w.epoch, w.senderAccountID, w.senderDeviceID, w.payload, w.sentAt))
            })
            "device.revoked" -> event(data?.str("deviceID")?.let { ServerEvent.DeviceRevoked(it) })
            "call.ring" -> event(data?.let { d ->
                val video = (d["video"] as? JsonPrimitive)?.booleanOrNull
                val c = d.str("callID"); val a = d.str("fromAccountID"); val f = d.str("fromDeviceID")
                val payload = d.bytes("payload")
                if (c == null || a == null || f == null || video == null || payload == null) null
                else ServerEvent.CallRing(c, a, f, video, payload)
            })
            "call.answered" -> event(data?.let { d ->
                val c = d.str("callID"); val dev = d.str("deviceID"); val p = d.bytes("payload")
                if (c == null || dev == null || p == null) null else ServerEvent.CallAnswered(c, dev, p)
            })
            "call.signal" -> event(data?.let { d ->
                val c = d.str("callID"); val dev = d.str("fromDeviceID"); val p = d.bytes("payload")
                if (c == null || dev == null || p == null) null else ServerEvent.CallSignal(c, dev, p)
            })
            "call.ended" -> event(data?.let { d ->
                val c = d.str("callID"); val r = d.str("reason")
                if (c == null || r == null) null else ServerEvent.CallEnded(c, r)
            })
            "ack" -> if (id == null) bad else IncomingFrame.Ack(id, data)
            "error" -> {
                val code = data?.str("code")
                if (id == null || code == null) bad else IncomingFrame.Error(id, code)
            }
            else -> null
        }
    } catch (_: Exception) {
        bad
    }
}

internal fun encodeFrame(type: String, id: String, data: JsonObject): String =
    ServerJson.json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("type", type)
        put("id", id)
        put("data", data)
    })
