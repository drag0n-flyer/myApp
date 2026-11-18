package org.example.app.net.preview

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import org.example.app.core.chats.LinkPreviewRule
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Answers for [host] with exactly the addresses that were checked, and for no other name: nothing is looked up again. */
internal class PinnedDns(host: String, private val addresses: List<InetAddress>) : Dns {
    private val host = host.lowercase().removeSuffix(".")
    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.lowercase().removeSuffix(".") != host) throw UnknownHostException(hostname)
        return addresses
    }
}

/**
 * The HTTP client already in the build (OkHttp), set up for one preview request: TLS only (no cleartext), connects only
 * to the checked addresses, no proxy (a proxy would resolve the name itself), no cookies, no credentials, no cache, no
 * redirects, no retry, a pool of its own that is not kept.
 */
class OkHttpPreviewTransport(private val base: OkHttpClient = OkHttpClient()) : PreviewTransport {
    companion object {
        /** A generic browser string: nothing in it names this app. */
        internal const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36"

        internal fun configure(base: OkHttpClient, host: String, addresses: List<InetAddress>, timeoutMs: Long): OkHttpClient =
            base.newBuilder()
                .dns(PinnedDns(host, addresses))
                .proxy(Proxy.NO_PROXY)
                .cookieJar(CookieJar.NO_COOKIES)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .cache(null)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
                .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()

        /** Only what a page needs: no cookie, no authorization, no referrer. */
        internal fun request(url: okhttp3.HttpUrl, accept: String): Request =
            Request.Builder().url(url).get().header("Accept", accept).header("User-Agent", USER_AGENT).build()

        /** All of [input] up to [max] bytes, or null when there is more than that. */
        internal fun readCapped(input: InputStream, max: Int): ByteArray? {
            val out = java.io.ByteArrayOutputStream(minOf(max, 16_384))
            val buffer = ByteArray(8_192)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > max) return null
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }

    override suspend fun get(url: String, host: String, addresses: List<InetAddress>, maxBytes: Int, timeoutMs: Long, accept: String): PreviewResponse =
        withContext(Dispatchers.IO) {
            val httpUrl = url.toHttpUrlOrNull()
            // OkHttp's reading of the address must agree with ours: https, no user, the checked host, port 443.
            if (httpUrl == null || !httpUrl.isHttps || httpUrl.port != 443 || httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) {
                throw IOException("not a plain https address")
            }
            // OkHttp must read the same host the fetcher checked, and it must be a name: never an address in any notation.
            if (httpUrl.host != host.lowercase().removeSuffix(".") || !LinkPreviewRule.hostNameAllowed(httpUrl.host)) {
                throw IOException("not the host that was checked")
            }
            val call = configure(base, httpUrl.host, addresses, timeoutMs).newCall(request(httpUrl, accept))
            val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
            try {
                call.execute().use { response ->
                    val status = response.code
                    val location = response.header("Location")
                    val type = response.header("Content-Type")
                    val declared = response.body.contentLength().takeIf { it >= 0 }
                    if (status != 200) return@use PreviewResponse(status, location, type, declared, null, false)
                    if (declared != null && declared > maxBytes) return@use PreviewResponse(status, location, type, declared, null, true)
                    val body = readCapped(response.body.byteStream(), maxBytes)
                    PreviewResponse(status, location, type, declared, body, body == null)
                }
            } finally {
                handle?.dispose()
            }
        }
}
