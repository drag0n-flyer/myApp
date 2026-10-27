package org.example.app.net.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.example.app.contracts.MemorySecretStore
import org.example.app.net.ServerClient
import org.example.app.net.Session
import org.example.app.net.SessionStore
import kotlin.test.AfterTest

/** MockWebServer plus a signed-in client. Replies in tests are shaped like the Go handlers. */
abstract class ApiTestBase {
    protected val server = MockWebServer()
    private val store = SessionStore(MemorySecretStore())

    protected val client: ServerClient by lazy {
        server.start()
        store.save(Session("example.org", "a1", "d1", "tok"))
        ServerClient(server.url("/"), store, OkHttpClient())
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    protected fun reply(code: Int = 200, body: String = "") {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    protected fun next(): RecordedRequest = server.takeRequest()

    protected fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body.readUtf8()) as JsonObject
}
