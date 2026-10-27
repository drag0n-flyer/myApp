package org.example.app.net.live

import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.example.app.contracts.ConnectionState
import org.example.app.contracts.MemorySecretStore
import org.example.app.contracts.ServerEvent
import org.example.app.contracts.ServerException
import org.example.app.net.ServerClient
import org.example.app.net.Session
import org.example.app.net.SessionStore
import org.example.app.net.Base64Url
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Virtual time for the connection's own timers; sockets still run in real time. */
class ManualClock : LiveClock {
    private class Waiter(val deadline: Long, val done: CompletableDeferred<Unit>)

    private val lock = Any()
    private var now = 0L
    private val waiters = mutableListOf<Waiter>()
    val requested = mutableListOf<Long>()

    override suspend fun delay(millis: Long) {
        val w = synchronized(lock) {
            requested += millis
            Waiter(now + millis, CompletableDeferred()).also { waiters += it }
        }
        try {
            w.done.await()
        } finally {
            synchronized(lock) { waiters -= w }
        }
    }

    fun advance(millis: Long) {
        val due = synchronized(lock) {
            now += millis
            waiters.filter { it.deadline <= now }
        }
        due.forEach { it.done.complete(Unit) }
    }

    fun sleepers(): Int = synchronized(lock) { waiters.size }
    fun requestedCopy(): List<Long> = synchronized(lock) { requested.toList() }
}

class LiveConnectionTest {
    private val server = MockWebServer()
    private val store = SessionStore(MemorySecretStore())
    private val clock = ManualClock()
    private val serverSockets = LinkedBlockingQueue<WebSocket>()
    private val serverMessages = LinkedBlockingQueue<String>()
    private var unauthorized = 0
    private var live: LiveConnection? = null
    private val rawServers = mutableListOf<ServerSocket>()

    init {
        store.save(Session("example.org", "a1", "d1", "secret-token"))
    }

    @AfterTest
    fun tearDown() {
        live?.stop()
        rawServers.forEach { it.close() }
        server.shutdown()
    }

    private val acceptingListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSockets.add(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            serverMessages.add(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }
    }

    private fun upgrade(listener: WebSocketListener = acceptingListener) {
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
    }

    private fun connection(pingMs: Long = 25_000): LiveConnection {
        if (!serverStarted) { server.start(); serverStarted = true }
        val client = ServerClient(server.url("/"), store, OkHttpClient())
        client.setOnUnauthorized { unauthorized++ }
        return LiveConnection(client, OkHttpClient(), clock, Random(1), pingIntervalMillis = pingMs).also { live = it }
    }

    private var serverStarted = false

    private fun <T> eventually(what: String, block: () -> T?): T {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < end) {
            block()?.let { return it }
            Thread.sleep(10)
        }
        fail("timed out: $what")
    }

    private fun awaitState(c: LiveConnection, s: ConnectionState) = eventually("state $s") { if (c.states.value == s) s else null }

    private fun awaitSleepers(n: Int) = eventually("$n sleepers") { if (clock.sleepers() >= n) n else null }

    @Test
    fun decodesEveryServerFrame() = runBlocking {
        upgrade()
        val c = connection()
        val events = async(Dispatchers.Default) { withTimeout(10_000) { c.events.take(6).toList() } }
        c.start()
        val ws = eventually("server socket") { serverSockets.poll() }
        val p = Base64Url.encode(byteArrayOf(1, 2, 3))
        ws.send("""{"type":"inbox","data":{"cursor":7,"kind":"message","conversationID":"cv1","seq":3,"epoch":2,"senderAccountID":"a2","senderDeviceID":"d2","payload":"$p","sentAt":"2026-10-28T10:00:00.123456789Z"}}""")
        ws.send("""{"type":"device.revoked","data":{"deviceID":"d9"}}""")
        ws.send("""{"type":"call.ring","data":{"callID":"k1","fromAccountID":"a2","fromDeviceID":"d2","video":true,"payload":"$p"}}""")
        ws.send("""{"type":"call.answered","data":{"callID":"k1","deviceID":"d3","payload":"$p"}}""")
        ws.send("""{"type":"call.signal","data":{"callID":"k1","fromDeviceID":"d4","payload":"$p"}}""")
        ws.send("""{"type":"call.ended","data":{"callID":"k1","reason":"hangup"}}""")
        val got = events.await()
        val inbox = got[0] as ServerEvent.Inbox
        assertEquals(7L, inbox.item.cursor); assertEquals("cv1", inbox.item.conversationId); assertEquals(3L, inbox.item.seq)
        assertContentEquals(byteArrayOf(1, 2, 3), inbox.item.payload)
        assertEquals("d9", (got[1] as ServerEvent.DeviceRevoked).deviceId)
        val ring = got[2] as ServerEvent.CallRing
        assertEquals("k1", ring.callId); assertEquals("a2", ring.fromAccountId); assertEquals("d2", ring.fromDeviceId); assertTrue(ring.video)
        assertContentEquals(byteArrayOf(1, 2, 3), ring.payload)
        val ans = got[3] as ServerEvent.CallAnswered
        assertEquals("d3", ans.deviceId)
        val sig = got[4] as ServerEvent.CallSignal
        assertEquals("d4", sig.fromDeviceId); assertEquals("k1", sig.callId)
        val ended = got[5] as ServerEvent.CallEnded
        assertEquals("hangup", ended.reason)
        // The token went in the header, on the right path.
        val req = server.takeRequest()
        assertEquals("/v1/ws", req.path)
        assertEquals("Bearer secret-token", req.getHeader("Authorization"))
    }

    @Test
    fun unknownAndMalformedFramesAreDropped() = runBlocking {
        upgrade()
        val c = connection()
        val ev = async(Dispatchers.Default) { withTimeout(10_000) { c.events.first() } }
        c.start()
        val ws = eventually("server socket") { serverSockets.poll() }
        ws.send("not json")
        ws.send("""{"type":"mystery","data":{}}""")
        ws.send("""{"type":"call.ended","data":{"callID":1}}""")
        ws.send("""{"type":"call.ring","data":{"callID":"k","fromAccountID":"a","fromDeviceID":"d","video":false,"payload":"!!"}}""")
        ws.send("""{"type":"call.ended","data":{"callID":"k2","reason":"missed"}}""")
        assertEquals("k2", (ev.await() as ServerEvent.CallEnded).callId)
    }

    @Test
    fun ackMatchesId() = runBlocking {
        upgrade()
        val c = connection()
        c.start()
        val ws = eventually("server socket") { serverSockets.poll() }
        awaitState(c, ConnectionState.Connected)
        val first = async(Dispatchers.Default) { c.send("call.status", buildJsonObject { put("callID", "x1") }) }
        val m1 = eventually("m1") { serverMessages.poll() }
        val second = async(Dispatchers.Default) { runCatching { c.send("call.hangup", buildJsonObject { put("callID", "x2") }) } }
        val m2 = eventually("m2") { serverMessages.poll() }
        val j1 = ServerJsonParse(m1); val j2 = ServerJsonParse(m2)
        assertEquals("c1", j1["id"]!!.jsonPrimitive.content)
        assertEquals("c2", j2["id"]!!.jsonPrimitive.content)
        assertEquals("call.status", j1["type"]!!.jsonPrimitive.content)
        // Answer out of order.
        ws.send("""{"type":"error","id":"c2","data":{"code":"not_found"}}""")
        ws.send("""{"type":"ack","id":"c1","data":{"state":"ringing"}}""")
        assertEquals("ringing", first.await()!!["state"]!!.jsonPrimitive.content)
        val e = second.await().exceptionOrNull() as ServerException
        assertEquals("not_found", e.code)
    }

    @Test
    fun ackWithoutDataReturnsNull() = runBlocking {
        upgrade()
        val c = connection()
        c.start()
        val ws = eventually("server socket") { serverSockets.poll() }
        awaitState(c, ConnectionState.Connected)
        val r = async(Dispatchers.Default) { c.send("call.hangup", JsonObject(emptyMap())) }
        eventually("m") { serverMessages.poll() }
        ws.send("""{"type":"ack","id":"c1"}""")
        assertNull(r.await())
    }

    @Test
    fun sendWhileNotConnectedFails() = runBlocking {
        val c = connection()
        val e = assertFailsWith<ServerException> { c.send("call.hangup", JsonObject(emptyMap())) }
        assertEquals("network", e.code)
    }

    @Test
    fun sendTimesOutAfter10s() = runBlocking {
        upgrade()
        val c = connection()
        c.start()
        eventually("server socket") { serverSockets.poll() }
        awaitState(c, ConnectionState.Connected)
        val r = async(Dispatchers.Default) { runCatching { c.send("call.status", JsonObject(emptyMap())) } }
        awaitSleepers(2) // the 30 s stability timer and the 10 s send timer
        clock.advance(9_999)
        Thread.sleep(100)
        assertTrue(r.isActive)
        clock.advance(1)
        val e = r.await().exceptionOrNull() as ServerException
        assertEquals("timeout", e.code)
        // A late ack for the timed-out id changes nothing.
        assertEquals(ConnectionState.Connected, c.states.value)
    }

    @Test
    fun pendingSendFailsWhenSocketDrops() = runBlocking {
        upgrade()
        val c = connection()
        c.start()
        val ws = eventually("server socket") { serverSockets.poll() }
        awaitState(c, ConnectionState.Connected)
        val r = async(Dispatchers.Default) { runCatching { c.send("call.status", JsonObject(emptyMap())) } }
        eventually("m") { serverMessages.poll() }
        ws.close(1001, "bye")
        assertEquals("network", (r.await().exceptionOrNull() as ServerException).code)
    }

    @Test
    fun reconnectsAfterDropAndBacksOff() = runBlocking {
        upgrade(); upgrade()
        val c = connection()
        c.start()
        eventually("first") { serverSockets.poll() }.close(1001, "x")
        awaitState(c, ConnectionState.Disconnected)
        awaitSleepers(1)
        assertEquals(1, server.requestCount)
        // Random(1) draws; the wait is within [0, 1000] ms.
        val wait = clock.requestedCopy().last { it != 30_000L }
        assertTrue(wait in 0..1000, "wait=$wait")
        clock.advance(1000)
        eventually("second") { serverSockets.poll() }
        awaitState(c, ConnectionState.Connected)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun resetsAfterStableConnection() = runBlocking {
        upgrade(); upgrade(); upgrade(); upgrade()
        if (!serverStarted) { server.start(); serverStarted = true }
        val client = ServerClient(server.url("/"), store, OkHttpClient())
        // Full range: the wait equals the bound.
        val c = LiveConnection(client, OkHttpClient(), clock, object : Random() {
            override fun nextBits(bitCount: Int) = -1 ushr (32 - bitCount)
            override fun nextDouble() = 1.0
        }).also { live = it }
        fun backoffWaits() = clock.requestedCopy().filter { it != 30_000L && it != 10_000L }
        c.start()
        eventually("s1") { serverSockets.poll() }.close(1001, "x")
        awaitSleepers(1); eventually("w1") { if (backoffWaits().size == 1) 1 else null }
        clock.advance(1000)
        // Second connection dies before it is stable.
        eventually("s2") { serverSockets.poll() }.close(1001, "x")
        eventually("w2") { if (backoffWaits().size == 2) 1 else null }
        clock.advance(2000)
        val s3 = eventually("s3") { serverSockets.poll() }
        awaitState(c, ConnectionState.Connected)
        awaitSleepers(1)
        clock.advance(30_000) // the third connection is stable now
        Thread.sleep(100)
        s3.close(1001, "x")
        eventually("w3") { if (backoffWaits().size == 3) 1 else null }
        assertEquals(listOf(1000L, 2000L, 1000L), backoffWaits())
    }

    @Test
    fun replaced4001StopsWithoutReconnect() = runBlocking {
        upgrade(); upgrade()
        val c = connection()
        c.start()
        eventually("s") { serverSockets.poll() }.close(4001, "replaced")
        awaitState(c, ConnectionState.Replaced)
        clock.advance(120_000)
        Thread.sleep(300)
        assertEquals(1, server.requestCount)
        assertEquals(ConnectionState.Replaced, c.states.value)
        assertEquals(0, unauthorized)
    }

    @Test
    fun revoked4003ReportsRevoked() = runBlocking {
        upgrade(); upgrade()
        val c = connection()
        c.start()
        eventually("s") { serverSockets.poll() }.close(4003, "revoked")
        awaitState(c, ConnectionState.Revoked)
        eventually("signed out") { if (unauthorized == 1) 1 else null }
        assertNull(store.current)
        clock.advance(120_000)
        Thread.sleep(300)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun unauthorizedUpgradeReportsRevoked() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"x"}}"""))
        val c = connection()
        c.start()
        awaitState(c, ConnectionState.Revoked)
        eventually("signed out") { if (unauthorized == 1) 1 else null }
        assertNull(store.current)
    }

    @Test
    fun noSessionStaysDisconnected() = runBlocking {
        store.clear()
        val c = connection()
        c.start()
        Thread.sleep(200)
        assertEquals(ConnectionState.Disconnected, c.states.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun stopClosesAndDoesNotReconnect() = runBlocking {
        upgrade(); upgrade()
        val c = connection()
        c.start()
        awaitState(c, ConnectionState.Connected)
        c.stop()
        assertEquals(ConnectionState.Disconnected, c.states.value)
        val before = server.requestCount
        clock.advance(120_000)
        Thread.sleep(300)
        assertEquals(before, server.requestCount)
        // start again works
        c.start()
        awaitState(c, ConnectionState.Connected)
        assertEquals(before + 1, server.requestCount)
    }

    @Test
    fun requestsCompression() {
        upgrade()
        val c = connection()
        c.start()
        val req = server.takeRequest(10, TimeUnit.SECONDS)
        assertNotNull(req)
        assertTrue(req.getHeader("Sec-WebSocket-Extensions")!!.contains("permessage-deflate"))
    }

    @Test
    fun refusesRedirects() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://evil.example/v1/ws"))
        val c = connection()
        c.start()
        awaitSleepers(1) // fails and backs off; the token never leaves
        assertEquals(1, server.requestCount)
        assertEquals(ConnectionState.Disconnected, c.states.value)
    }

    @Test
    fun missingPongReconnects() = runBlocking {
        // A server that completes the handshake and then never answers a ping.
        val raw = ServerSocket(0).also { rawServers += it }
        val accepted = LinkedBlockingQueue<Socket>()
        Thread {
            try {
                while (true) {
                    val s = raw.accept()
                    val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    var key = ""
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) key = line.substringAfter(":").trim()
                    }
                    val accept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                    )
                    s.getOutputStream().write(
                        ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
                    )
                    s.getOutputStream().flush()
                    accepted.add(s)
                    // Never read or write again, so no pong ever comes back.
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
        val client = ServerClient(okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(raw.localPort).build(), store, OkHttpClient())
        val c = LiveConnection(client, OkHttpClient(), clock, Random(1), pingIntervalMillis = 150).also { live = it }
        c.start()
        eventually("first connection") { accepted.poll() }
        awaitState(c, ConnectionState.Connected)
        // No pong: the connection is declared dead and the backoff timer starts.
        awaitState(c, ConnectionState.Disconnected)
        awaitSleepers(1)
        clock.advance(1000)
        eventually("second connection") { accepted.poll() }
        Unit
    }

    @Suppress("FunctionName")
    private fun ServerJsonParse(s: String): JsonObject =
        org.example.app.net.ServerJson.json.parseToJsonElement(s).jsonObject
}
