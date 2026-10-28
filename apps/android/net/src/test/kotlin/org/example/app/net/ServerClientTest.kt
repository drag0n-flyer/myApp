package org.example.app.net

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.asCoroutineDispatcher
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

    private val unauthorized = """{"error":{"code":"unauthorized","message":"x"}}"""

    /** Holds every request until [n] have arrived, so all of them read the session before any 401 is sent. */
    private fun holdUntil(n: Int, response: () -> MockResponse) {
        val arrived = java.util.concurrent.CountDownLatch(n)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                arrived.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return response()
            }
        }
    }

    @Test
    fun clearsSessionOnceOn401() = runBlocking {
        holdUntil(5) { MockResponse().setResponseCode(401).setBody(unauthorized) }
        val c = client()
        store.save(session)
        val calls = AtomicInteger(0)
        c.setOnUnauthorized { calls.incrementAndGet() }
        val results = (1..5).map { async(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.get(Auth.Session) } } }.awaitAll()
        results.forEach { assertEquals(401, (it.exceptionOrNull() as ServerException).status) }
        assertEquals(1, calls.get())
        // The five calls, then the one check that the 401 is real (GET /v1/me).
        assertEquals(6, server.requestCount)
        assertNull(store.current)
    }

    /** Ruling (decision log): a 401 proves revocation only if it is the server's own error and a second call agrees. */
    private fun answer(first: MockResponse, me: MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path == "/v1/me") me else first
        }
    }

    private suspend fun reasonFor(): Pair<SignOutReason?, Int> {
        val c = client()
        store.save(session)
        var reason: SignOutReason? = null
        c.setOnUnauthorized { reason = it }
        assertFailsWith<ServerException> { c.get(Auth.Session) }
        assertNull(store.current)
        return reason to server.requestCount
    }

    @Test
    fun confirmedServer401IsRevoked() = runBlocking {
        answer(MockResponse().setResponseCode(401).setBody(unauthorized), MockResponse().setResponseCode(401).setBody(unauthorized))
        val (reason, count) = reasonFor()
        assertEquals(SignOutReason.Revoked, reason)
        assertEquals(2, count)
        server.takeRequest()
        assertEquals("Bearer tok", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun unconfirmed401OnlySignsOut() = runBlocking {
        answer(MockResponse().setResponseCode(401).setBody(unauthorized), MockResponse().setResponseCode(200).setBody("{}"))
        assertEquals(SignOutReason.SessionEnded to 2, reasonFor())
    }

    @Test
    fun foreign401OnlySignsOutWithoutACheck() = runBlocking {
        answer(MockResponse().setResponseCode(401).setBody("<html>proxy</html>"), MockResponse().setResponseCode(401).setBody(unauthorized))
        assertEquals(SignOutReason.SessionEnded to 1, reasonFor())
    }

    @Test
    fun stale401AfterLoginDoesNotSignOut() = runBlocking {
        val arrived = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return MockResponse().setResponseCode(401).setBody(unauthorized)
            }
        }
        val c = client()
        store.save(session)
        val calls = AtomicInteger(0)
        c.setOnUnauthorized { calls.incrementAndGet() }
        val r = async(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.get(Auth.Session) } }
        arrived.await(10, java.util.concurrent.TimeUnit.SECONDS)
        val fresh = session.copy(token = "new")
        store.save(fresh)
        release.countDown()
        assertEquals(401, (r.await().exceptionOrNull() as ServerException).status)
        assertEquals(0, calls.get())
        assertEquals(fresh, store.current)
    }

    @Test
    fun reLoginRearms401Handling() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401).setBody(unauthorized)
        }
        val c = client()
        val calls = AtomicInteger(0)
        c.setOnUnauthorized { calls.incrementAndGet() }
        store.save(session)
        runCatching { c.get(Auth.Session) }
        assertEquals(1, calls.get())
        store.save(session.copy(token = "again"))
        runCatching { c.get(Auth.Session) }
        assertEquals(2, calls.get())
        assertNull(store.current)
    }

    @Test
    fun unauthenticated401DoesNotSignOut() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody(unauthorized))
        val c = client()
        store.save(session)
        val calls = AtomicInteger(0)
        c.setOnUnauthorized { calls.incrementAndGet() }
        assertFailsWith<ServerException> { c.get(Auth.None) }
        assertEquals(0, calls.get())
        assertEquals(session, store.current)
    }

    @Test
    fun failedClearIsRetriedOnNext401() = runBlocking {
        val failing = object : org.example.app.contracts.SecretStore by MemorySecretStore() {
            var failures = 1
            val inner = MemorySecretStore()
            override fun load(name: String) = inner.load(name)
            override fun save(name: String, value: ByteArray) = inner.save(name, value)
            override fun delete(name: String) {
                if (failures-- > 0) throw IllegalStateException("keystore busy")
                inner.delete(name)
            }
        }
        val st = SessionStore(failing)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401).setBody(unauthorized)
        }
        server.start()
        val c = ServerClient(server.url("/"), st, OkHttpClient())
        val calls = AtomicInteger(0)
        c.setOnUnauthorized { calls.incrementAndGet() }
        st.save(session)
        val e1 = assertFailsWith<ServerException> { c.get(Auth.Session) }
        assertEquals(401, e1.status)
        assertEquals(session, st.current)
        assertFailsWith<ServerException> { c.get(Auth.Session) }
        assertNull(st.current)
        assertEquals(2, calls.get())
    }

    @Test
    fun ioWorkRunsOnInjectedDispatcher() = runBlocking {
        val ioPool = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "net-io-test") }
        val threads = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val recording = object : org.example.app.contracts.SecretStore by MemorySecretStore() {
            val inner = MemorySecretStore()
            override fun load(name: String) = inner.load(name)
            override fun save(name: String, value: ByteArray) = inner.save(name, value)
            override fun delete(name: String) {
                threads += Thread.currentThread().name.substringBefore(' ')
                inner.delete(name)
            }
        }
        val st = SessionStore(recording)
        server.start()
        val c = ServerClient(server.url("/"), st, OkHttpClient(), ioPool.asCoroutineDispatcher())
        st.save(session)
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody(unauthorized))
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller-test") }.asCoroutineDispatcher()
        kotlinx.coroutines.withContext(caller) {
            c.request("GET", "/v1/ping", null, Auth.Session, ThreadRecording(threads))
            runCatching { c.get(Auth.Session) }
        }
        ioPool.shutdown()
        assertEquals(listOf("net-io-test", "net-io-test"), threads.toList())
    }

    private class ThreadRecording(val threads: java.util.Queue<String>) : kotlinx.serialization.DeserializationStrategy<Pong> {
        override val descriptor get() = Pong.serializer().descriptor
        override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Pong {
            threads += Thread.currentThread().name.substringBefore(' ')
            return Pong.serializer().deserialize(decoder)
        }
    }

    @Test
    fun redirectsAreNotFollowedAndAreErrors() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other").toString()))
        val c = client()
        store.save(session)
        val e = assertFailsWith<ServerException> { c.get(Auth.Session) }
        assertEquals(302, e.status)
        assertEquals("redirect", e.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun onlyBaseUrlHostIsReachable() = runBlocking {
        val c = client()
        store.save(session)
        for (p in listOf("//evil.example/x", "http://evil.example/x", "https://evil.example/x", "v1/x", "")) {
            val e = assertFailsWith<ServerException>(p) { c.request("GET", p, null, Auth.Session, Pong.serializer()) }
            assertEquals("invalid_path", e.code, p)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun timeouts() {
        val c = ServerClient("example.org", store, OkHttpClient())
        assertEquals(30_000, c.jsonHttp.connectTimeoutMillis)
        assertEquals(30_000, c.jsonHttp.readTimeoutMillis)
        assertEquals(30_000, c.jsonHttp.writeTimeoutMillis)
        assertEquals(30_000, c.jsonHttp.callTimeoutMillis)
        assertEquals(30_000, c.rawHttp.readTimeoutMillis)
        assertEquals(0, c.rawHttp.callTimeoutMillis)
        assertEquals(false, c.rawHttp.followRedirects)
        assertEquals(false, c.jsonHttp.followSslRedirects)
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
