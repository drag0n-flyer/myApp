package org.example.app.net.preview

import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.example.app.core.chats.LinkPreviewCard
import org.example.app.core.chats.LinkPreviewParse
import org.example.app.core.chats.LinkPreviewRule

/** What one request gave back. A redirect carries [location] and no body. [tooLarge] is true when the body went over the cap. */
class PreviewResponse(val status: Int, val location: String?, val contentType: String?, val contentLength: Long?, val body: ByteArray?, val tooLarge: Boolean)

/** Turns a name into addresses. The fetcher checks the answer before anything connects. */
fun interface PreviewResolver {
    suspend fun resolve(host: String): List<InetAddress>
}

/**
 * One GET of [url], whose host must be [host] (the one the fetcher checked). It connects only to [addresses] (the ones the fetcher checked), over TLS only, sends no cookies and no
 * credentials, does not follow redirects, reads at most [maxBytes] and gives up after [timeoutMs].
 */
fun interface PreviewTransport {
    suspend fun get(url: String, host: String, addresses: List<InetAddress>, maxBytes: Int, timeoutMs: Long, accept: String): PreviewResponse
}

/** Re-encodes any picture the system can read as a JPEG (the caller checks its size), or null when it cannot. */
fun interface ImageShrinker {
    fun toJpeg(bytes: ByteArray): ByteArray?
}

/**
 * Makes a link preview on the sender's device, when the sender asked for one. Every rule that decides whether a request
 * may go out lives in [LinkPreviewRule]; this class only applies them, in this order, before and on every hop: the URL
 * is https on port 443 with a public-looking name; the name is resolved by us and every address must be public; the
 * request connects only to those addresses; a redirect must stay on the same host and goes through all of it again.
 *
 * Limits: 5 seconds for everything (page and image together), 512 KiB per body, 3 redirects, a page must be HTML.
 * Anything that goes wrong gives null: the message then sends without a preview.
 */
class LinkPreviewFetcher(
    private val resolver: PreviewResolver,
    private val transport: PreviewTransport,
    private val shrinker: ImageShrinker,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val totalMs: Long = TOTAL_MS,
) {
    companion object {
        const val TOTAL_MS = 5_000L
        const val MAX_BODY = 524_288
        const val MAX_REDIRECTS = 3
        const val IMAGE_MAX = 8_192
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val PAGE_TYPES = setOf("text/html", "application/xhtml+xml")
        private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    }

    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Loaded(val url: String, val response: PreviewResponse)

    /** Everything runs off the caller's dispatcher (parsing and picture work included), so a caller on the main thread is never held. */
    suspend fun fetch(url: String): LinkPreviewCard? {
        if (LinkPreviewRule.checkUrl(url) == null) return null
        return try {
            withContext(Dispatchers.Default) { run(url, nowMs() + totalMs) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun run(url: String, deadline: Long): LinkPreviewCard? {
        val page = load(url, "text/html,application/xhtml+xml", deadline, PAGE_TYPES) ?: return null
        val parsed = LinkPreviewParse.fromHTML(page.response.body ?: return null, MAX_BODY) ?: return null
        val image = parsed.imageUrl?.let { fetchImage(page.url, it, deadline) }
        return LinkPreviewRule.cardFor(url, LinkPreviewCard(url, parsed.title, parsed.desc, image))
    }

    /** The image is optional: any problem drops it and keeps the preview. It may live on another public host. */
    private suspend fun fetchImage(pageUrl: String, address: String, deadline: Long): ByteArray? {
        try {
            val url = try { URI(pageUrl).resolve(address).toString() } catch (_: Exception) { return null }
            if (deadline - nowMs() <= 0) return null
            val loaded = load(url, "image/jpeg,image/png,image/webp,image/gif", deadline, IMAGE_TYPES) ?: return null
            val jpeg = shrinker.toJpeg(loaded.response.body ?: return null) ?: return null
            return jpeg.takeIf { it.size in 2..IMAGE_MAX && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return null // including an image too big to decode
        }
    }

    /**
     * The lookup runs detached on the IO threads and is awaited with the time that is left. A system lookup cannot be
     * interrupted, so a slow one is abandoned: this returns null at the deadline and the lookup finishes (or not) alone.
     */
    private suspend fun lookup(host: String, timeoutMs: Long): List<InetAddress>? {
        val job = lookupScope.async { resolver.resolve(host) }
        val found = withTimeoutOrNull(timeoutMs) { job.await() }
        if (found == null) job.cancel()
        return found
    }

    private suspend fun load(start: String, accept: String, deadline: Long, types: Set<String>): Loaded? {
        var current = start
        for (hop in 0..MAX_REDIRECTS) {
            val host = LinkPreviewRule.checkUrl(current) ?: return null
            val resolveTime = deadline - nowMs()
            if (resolveTime <= 0) return null
            val addresses = lookup(host, resolveTime) ?: return null
            if (!LinkPreviewRule.allowedHost(host, addresses.map { it.address })) return null
            val requestTime = deadline - nowMs()
            if (requestTime <= 0) return null
            val response = transport.get(current, host, addresses, MAX_BODY, requestTime, accept)
            if (response.status in REDIRECTS) {
                current = LinkPreviewRule.redirectTarget(current, response.location ?: return null) ?: return null
                continue
            }
            if (response.status != 200 || response.tooLarge) return null
            if ((response.contentLength ?: 0L) > MAX_BODY) return null
            val type = response.contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
            if (type !in types) return null
            val body = response.body ?: return null
            if (body.isEmpty() || body.size > MAX_BODY) return null
            return Loaded(current, response)
        }
        return null
    }
}

/** Resolves names with the system resolver, off the calling thread. Every answer goes through the address checks before anything connects. */
object SystemPreviewResolver : PreviewResolver {
    override suspend fun resolve(host: String): List<InetAddress> =
        InetAddress.getAllByName(host).toList() // the fetcher runs this detached on IO and abandons it at the deadline
}

/** The fetcher on the HTTP client in the build and the system resolver; the platform gives the image encoder. */
fun systemLinkPreviewFetcher(shrinker: ImageShrinker): LinkPreviewFetcher =
    LinkPreviewFetcher(SystemPreviewResolver, OkHttpPreviewTransport(), shrinker)
