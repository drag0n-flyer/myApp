package org.example.app.net.account

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/** A MockWebServer that answers by "METHOD /path". Unrouted requests get the server's 404 error shape. */
class FakeServer {
    val web = MockWebServer()
    private val routes = HashMap<String, (RecordedRequest) -> MockResponse>()
    val seen = CopyOnWriteArrayList<RecordedRequest>()

    init {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val h = routes["${request.method} ${request.path}"]
                    ?: return MockResponse().setResponseCode(404).setBody("""{"error":{"code":"not_found","message":"no route"}}""")
                return h(request)
            }
        }
        web.start()
    }

    fun route(key: String, h: (RecordedRequest) -> MockResponse) { routes[key] = h }
    fun json(key: String, body: String, code: Int = 200) = route(key) { MockResponse().setResponseCode(code).setBody(body) }
    fun error(key: String, status: Int, code: String) =
        json(key, """{"error":{"code":"$code","message":"x"}}""", status)

    fun requests(key: String) = seen.filter { "${it.method} ${it.path}" == key }
    fun bodyOf(key: String): String = requests(key).single().body.peek().readUtf8()
    fun shutdown() = web.shutdown()
}
