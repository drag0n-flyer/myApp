package org.example.app.calls.session

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.app.contracts.ConnectionState
import org.example.app.contracts.LiveSocket
import org.example.app.contracts.ServerEvent
import org.example.app.contracts.ServerException
import org.junit.Test

class LiveCallSignalingTest {
    private class FakeSocket(var reply: (String, JsonObject) -> JsonObject? = { _, _ -> null }) : LiveSocket {
        val sent = mutableListOf<Pair<String, JsonObject>>()
        override val events = MutableSharedFlow<ServerEvent>()
        override val states: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected)
        override suspend fun send(type: String, data: JsonObject): JsonObject? {
            sent += type to data
            return reply(type, data)
        }
    }

    @Test
    fun invitePayloadIsBase64UrlWithoutPadding() = runTest {
        val socket = FakeSocket()
        LiveCallSignaling(socket) { emptyList() }.invite("c1", "bob", video = true, payload = byteArrayOf(-5, -1))
        val (type, data) = socket.sent.single()
        assertEquals("call.invite", type)
        assertEquals("c1", data["callID"]!!.jsonPrimitive.content)
        assertEquals("bob", data["to"]!!.jsonPrimitive.content)
        assertEquals("true", data["video"]!!.jsonPrimitive.content)
        assertEquals("-_8", data["payload"]!!.jsonPrimitive.content)
    }

    @Test
    fun statusReadsTheAck() = runTest {
        val socket = FakeSocket { _, _ -> buildJsonObject { put("state", "ended"); put("reason", "declined") } }
        val signaling = LiveCallSignaling(socket) { emptyList() }
        assertEquals(CallStatus.Ended("declined"), signaling.status("c1"))
        socket.reply = { _, _ -> buildJsonObject { put("state", "ringing") } }
        assertEquals(CallStatus.Ringing, signaling.status("c1"))
        socket.reply = { _, _ -> buildJsonObject { put("state", "answered") } }
        assertEquals(CallStatus.Answered, signaling.status("c1"))
        socket.reply = { _, _ -> buildJsonObject { put("state", JsonPrimitive("weird")) } }
        assertEquals(CallStatus.Unknown, signaling.status("c1"))
        assertEquals("call.status", socket.sent.last().first)
    }

    @Test
    fun forgottenCallIsEnded() = runTest {
        val socket = FakeSocket { _, _ -> throw ServerException(0, "not_found", null, null) }
        assertIs<CallStatus.Ended>(LiveCallSignaling(socket) { emptyList() }.status("c1"))
    }

    @Test
    fun serverCallFramesBecomeCallEvents() = runTest(UnconfinedTestDispatcher()) {
        val socket = FakeSocket()
        val signaling = LiveCallSignaling(socket) { emptyList() }
        val got = mutableListOf<CallServerEvent>()
        val job = launch { signaling.events.collect { got += it } }
        socket.events.emit(ServerEvent.DeviceRevoked("d"))
        socket.events.emit(ServerEvent.CallEnded("c1", "hangup"))
        socket.events.emit(ServerEvent.CallSignal("c1", "d2", byteArrayOf(1)))
        job.cancel()
        assertEquals(CallServerEvent.Ended("c1", "hangup"), got.first())
        assertEquals("d2", assertIs<CallServerEvent.Signal>(got[1]).fromDeviceID)
        assertEquals(2, got.size)
    }
}
