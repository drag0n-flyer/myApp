package org.example.app.net

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.example.app.contracts.MemorySecretStore
import org.example.app.contracts.ServerException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import okhttp3.OkHttpClient

class ServerClientTest {
    @Serializable
    private class Pong(val ok: Boolean)

    private val server = MockWebServer()
    private val store = SessionStore(MemorySecretStore())
    private val session = Session("example.org", "a1", "d1", "tok")

    private fun client(): ServerClient {
        server.start()
        return ServerClient(server.url("/"), store, OkHttpClient())
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private suspend fun ServerClient.get(auth: Auth) =
        request("GET", "/v1/ping", null, auth, Pong.serializer())

    @Test
    fun errorEnvelopeMapsToServerException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"stale_epoch","message":"x","details":{"currentEpoch":3}}}"""))
        val e = assertFailsWith<ServerException> { client().get(Auth.None) }
        assertEquals(409, e.status)
        assertEquals("stale_epoch", e.code)
        assertEquals(3L, e.details?.get("currentEpoch"))
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        val u = assertFailsWith<ServerException> { client().get(Auth.None) }
        assertEquals("unknown", u.code)
        assertEquals(502, u.status)
    }

    @Test
    fun retryAfterParsed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", " 7 ").setBody("""{"error":{"code":"rate_limited","message":"slow"}}"""))
        val e = assertFailsWith<ServerException> { client().get(Auth.None) }
        assertEquals("rate_limited", e.code)
        assertEquals(7, e.retryAfter)
        assertNull(e.details)
    }

    @Test
    fun transportFailureIsNetwork() = runBlocking {
        val c = client()
        server.shutdown()
        val e = assertFailsWith<ServerException> { c.get(Auth.None) }
        assertEquals("network", e.code)
        assertEquals(0, e.status)
    }

    @Test
    fun bearerOnlyWhenAuthed() = runBlocking {
        val c = client()
        store.save(session)
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        c.get(Auth.None)
        c.request("POST", "/v1/x", buildJsonObject { put("k", "v") }, Auth.Session, Pong.serializer())
        val plain = server.takeRequest()
        val authed = server.takeRequest()
        assertNull(plain.getHeader("Authorization"))
        assertEquals("Bearer tok", authed.getHeader("Authorization"))
        assertEquals("""{"k":"v"}""", authed.body.readUtf8())
        assertEquals("application/json", authed.getHeader("Content-Type")?.substringBefore(";"))
    }

    @Test
    fun noSessionIsLocal401() = runBlocking {
        val c = client()
        val e = assertFailsWith<ServerException> { c.get(Auth.Session) }
        assertEquals(401, e.status)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun clearsSessionOnceOn401() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"x"}}""")
        }
        val c = client()
        store.save(session)
        val calls = AtomicInteger(0)
        c.setOnUnauthorized { calls.incrementAndGet() }
        val results = (1..5).map { async(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.get(Auth.Session) } } }.awaitAll()
        results.forEach { assertEquals(401, (it.exceptionOrNull() as ServerException).status) }
        assertEquals(1, calls.get())
        assertNull(store.current)
    }

    @Test
    fun baseUrlIsHttps() {
        val c = ServerClient("example.org", store, OkHttpClient())
        assertEquals("https://example.org/", c.baseUrl.toString())
    }

    @Test
    fun decodingFailureIsDecoding() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"nope":1}"""))
        val e = assertFailsWith<ServerException> { client().get(Auth.None) }
        assertEquals("decoding", e.code)
    }
}
