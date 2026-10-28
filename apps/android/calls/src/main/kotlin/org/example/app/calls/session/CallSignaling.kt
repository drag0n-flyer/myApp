package org.example.app.calls.session

import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.app.contracts.LiveSocket
import org.example.app.contracts.ServerEvent
import org.example.app.contracts.ServerException

/** The server side of a call: client call frames out, server call frames in. Mirrors the iPhone's `CallSignaling`. */
interface CallSignaling {
    val events: Flow<CallServerEvent>
    suspend fun invite(callID: String, to: String, video: Boolean, payload: ByteArray)
    suspend fun answer(callID: String, payload: ByteArray)
    suspend fun signal(callID: String, payload: ByteArray)
    suspend fun decline(callID: String)
    suspend fun hangup(callID: String)

    /** Where the call stands on the server (`call.status`). Throws when the server cannot be asked. */
    suspend fun status(callID: String): CallStatus
    suspend fun iceServers(): List<IceServer>
}

/** [CallSignaling] over the live WebSocket (server design §7.3). Payloads are base64url without padding. */
class LiveCallSignaling(
    private val socket: LiveSocket,
    private val turn: suspend () -> List<IceServer>,
) : CallSignaling {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override val events: Flow<CallServerEvent> = socket.events.mapNotNull { e ->
        when (e) {
            is ServerEvent.CallRing -> CallServerEvent.Ring(e.callId, e.fromAccountId, e.fromDeviceId, e.video, e.payload)
            is ServerEvent.CallAnswered -> CallServerEvent.Answered(e.callId, e.deviceId, e.payload)
            is ServerEvent.CallSignal -> CallServerEvent.Signal(e.callId, e.fromDeviceId, e.payload)
            is ServerEvent.CallEnded -> CallServerEvent.Ended(e.callId, e.reason)
            is ServerEvent.Inbox, is ServerEvent.DeviceRevoked -> null
        }
    }

    override suspend fun invite(callID: String, to: String, video: Boolean, payload: ByteArray) {
        socket.send(CallFrame.INVITE, buildJsonObject {
            put("callID", callID); put("to", to); put("video", video); put("payload", encoder.encodeToString(payload))
        })
    }

    override suspend fun answer(callID: String, payload: ByteArray) {
        socket.send(CallFrame.ANSWER, withPayload(callID, payload))
    }

    override suspend fun signal(callID: String, payload: ByteArray) {
        socket.send(CallFrame.SIGNAL, withPayload(callID, payload))
    }

    override suspend fun decline(callID: String) {
        socket.send(CallFrame.DECLINE, buildJsonObject { put("callID", callID) })
    }

    override suspend fun hangup(callID: String) {
        socket.send(CallFrame.HANGUP, buildJsonObject { put("callID", callID) })
    }

    /**
     * `not_found` (the server no longer knows the call) reads as [CallStatus.Unknown], as on iPhone: the call rings
     * and the ring deadline ends it.
     */
    override suspend fun status(callID: String): CallStatus {
        val ack = try {
            socket.send(STATUS, buildJsonObject { put("callID", callID) })
        } catch (e: ServerException) {
            if (e.code == "not_found") return CallStatus.Unknown
            throw e
        }
        return when ((ack?.get("state") as? JsonPrimitive)?.content) {
            "ringing" -> CallStatus.Ringing
            "answered" -> CallStatus.Answered
            "ended" -> CallStatus.Ended((ack["reason"] as? JsonPrimitive)?.content ?: "")
            else -> CallStatus.Unknown
        }
    }

    override suspend fun iceServers(): List<IceServer> = turn()

    private fun withPayload(callID: String, payload: ByteArray): JsonObject =
        buildJsonObject { put("callID", callID); put("payload", encoder.encodeToString(payload)) }

    private companion object {
        const val STATUS = "call.status"
    }
}
