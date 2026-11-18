package org.example.app.net.preview

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class OkHttpPreviewTransportTest {
    private val address = InetAddress.getByName("93.184.216.34")

    private fun client(timeout: Long = 4_000) = OkHttpPreviewTransport.configure(OkHttpClient(), "example.org", listOf(address), timeout)

    @Test fun theClientHasNoCookiesCredentialsProxyCacheRedirectsOrCleartext() {
        val c = client()
        assertSame(CookieJar.NO_COOKIES, c.cookieJar)
        assertSame(Authenticator.NONE, c.authenticator)
        assertSame(Authenticator.NONE, c.proxyAuthenticator)
        assertEquals(Proxy.NO_PROXY, c.proxy)
        assertNull(c.cache)
        assertFalse(c.followRedirects)
        assertFalse(c.followSslRedirects)
        assertFalse(c.retryOnConnectionFailure)
        assertEquals(listOf(ConnectionSpec.MODERN_TLS), c.connectionSpecs)
        assertEquals(4_000, c.callTimeoutMillis)
        assertTrue(c.interceptors.isEmpty() && c.networkInterceptors.isEmpty())
    }

    @Test fun theRequestCarriesNoCookieAuthorizationOrReferrer() {
        val r = OkHttpPreviewTransport.request("https://example.org/a".toHttpUrl(), "text/html")
        assertEquals("GET", r.method)
        assertEquals(setOf("accept", "user-agent"), r.headers.names().map { it.lowercase() }.toSet())
        assertEquals("https", r.url.scheme)
    }

    @Test fun pinnedDnsAnswersOnlyForItsHostAndOnlyWithTheCheckedAddresses() {
        val dns = PinnedDns("Example.org.", listOf(address))
        assertEquals(listOf(address), dns.lookup("example.org"))
        assertEquals(listOf(address), dns.lookup("EXAMPLE.ORG."))
        assertFailsWith<UnknownHostException> { dns.lookup("other.example") }
        assertFailsWith<UnknownHostException> { dns.lookup("localhost") }
        assertFailsWith<UnknownHostException> { dns.lookup("127.0.0.1") }
    }

    @Test fun readCappedReadsExactlyTheCapAndRefusesOneByteMore() {
        assertContentEquals(ByteArray(10) { 1 }, OkHttpPreviewTransport.readCapped(ByteArrayInputStream(ByteArray(10) { 1 }), 10))
        assertNull(OkHttpPreviewTransport.readCapped(ByteArrayInputStream(ByteArray(11)), 10))
        assertContentEquals(ByteArray(0), OkHttpPreviewTransport.readCapped(ByteArrayInputStream(ByteArray(0)), 10))
        assertNull(OkHttpPreviewTransport.readCapped(ByteArrayInputStream(ByteArray(11_000_000)), 524_288))
    }

    @Test fun anAddressThatIsNotPlainHttpsNeverReachesTheNetwork() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<title>x</title>"))
            server.start()
            val local = InetAddress.getByName("127.0.0.1")
            val transport = OkHttpPreviewTransport()
            for (url in listOf(
                "http://127.0.0.1:${server.port}/", "http://example.org/", "https://example.org:${server.port}/", "https://user@example.org/", "ftp://example.org/", "not a url",
            )) {
                assertFailsWith<IOException>(url) { transport.get(url, listOf(local), 524_288, 2_000, "text/html") }
            }
            assertEquals(0, server.requestCount, "nothing was sent")
        }
    }
}
