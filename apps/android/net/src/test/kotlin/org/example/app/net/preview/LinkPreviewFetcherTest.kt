package org.example.app.net.preview

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.example.app.core.chats.LinkPreviewCard

class LinkPreviewFetcherTest {
    private val page = """<html><head><title>Plain</title>
        <meta property="og:title" content="The Title"><meta property="og:description" content="The description">
        <meta property="og:image" content="https://cdn.example.net/i.png"></head></html>""".encodeToByteArray()
    private val jpeg = byteArrayOf(-1, -40, 9, 9)

    private fun html(body: ByteArray = page) = PreviewResponse(200, null, "text/html; charset=utf-8", body.size.toLong(), body, false)
    private fun image() = PreviewResponse(200, null, "image/png", 5, byteArrayOf(1, 2, 3, 4, 5), false)
    private fun redirect(to: String, status: Int = 302) = PreviewResponse(status, to, null, null, null, false)

    private class Rig(val handler: (String) -> PreviewResponse) {
        val dns = mutableMapOf(
            "example.org" to listOf("93.184.216.34"),
            "www.example.org" to listOf("93.184.216.35"),
            "cdn.example.net" to listOf("93.184.216.40"),
            "evil.example" to listOf("192.168.1.1"),
            "mixed.example" to listOf("93.184.216.34", "10.0.0.5"),
            "meta.example" to listOf("169.254.169.254"),
        )
        val resolved = mutableListOf<String>()
        val requests = mutableListOf<String>()
        val pinned = mutableListOf<List<String>>()
        var elapsed = 0L
        var shrunk: ByteArray? = byteArrayOf(-1, -40, 9, 9)
        val shrinkInputs = mutableListOf<ByteArray>()
        val fetcher = LinkPreviewFetcher(
            resolver = { host ->
                resolved += host
                (dns[host] ?: throw UnknownHostException(host)).map { InetAddress.getByName(it) }
            },
            transport = { url, addresses, maxBytes, timeoutMs, _ ->
                requests += url
                pinned += addresses.map { it.hostAddress!! }
                check(maxBytes == 524_288) { "the body cap is 512 KiB" }
                check(timeoutMs in 1..5_000) { "each call gets what is left of the 5 s: $timeoutMs" }
                handler(url)
            },
            shrinker = { bytes -> shrinkInputs += bytes; shrunk },
            nowMs = { elapsed },
        )
        fun fetch(url: String): LinkPreviewCard? = runBlocking { fetcher.fetch(url) }
    }

    private fun standard(url: String) = when {
        url.endsWith("/i.png") -> image()
        else -> html()
    }

    @Test fun aPageWithOpenGraphTagsGivesATitleDescriptionAndAnImage() {
        val rig = Rig(::standard)
        val card = assertNotNull(rig.fetch("https://example.org/a"))
        assertEquals("https://example.org/a", card.url)
        assertEquals("The Title", card.title)
        assertEquals("The description", card.desc)
        assertContentEquals(byteArrayOf(-1, -40, 9, 9), card.image)
        assertEquals(listOf("https://example.org/a", "https://cdn.example.net/i.png"), rig.requests)
        assertEquals(listOf(listOf("93.184.216.34"), listOf("93.184.216.40")), rig.pinned, "it connects only to the addresses it checked")
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), rig.shrinkInputs.single())
    }

    // Refused before any request: nothing is sent

    @Test fun unsafeAddressesAndSchemesAreRefusedWithoutARequest() {
        val urls = listOf(
            "http://example.org/", "https://127.0.0.1/", "https://[::1]/", "https://10.0.0.1/", "https://169.254.169.254/",
            "https://localhost/", "https://printer.local/", "https://user@example.org/", "https://example.org:8443/",
            "ftp://example.org/", "javascript:alert(1)", "", "https://" + "a".repeat(3_000) + ".example",
        )
        for (url in urls) {
            val rig = Rig(::standard)
            assertNull(rig.fetch(url), url)
            assertEquals(emptyList(), rig.requests, url)
            assertEquals(emptyList(), rig.resolved, "no name lookup either: $url")
        }
    }

    @Test fun aNameThatResolvesToAPrivateAddressIsRefusedBeforeConnecting() {
        for (host in listOf("evil.example", "mixed.example", "meta.example")) {
            val rig = Rig(::standard)
            assertNull(rig.fetch("https://$host/"), host)
            assertEquals(emptyList(), rig.requests, host)
        }
    }

    @Test fun aNameThatDoesNotResolveGivesNoPreview() {
        val rig = Rig(::standard)
        assertNull(rig.fetch("https://nowhere.example/"))
        assertEquals(emptyList(), rig.requests)
    }

    // Redirects

    @Test fun aRedirectOnTheSameHostIsFollowedAndCheckedAgain() {
        val rig = Rig { url -> if (url == "https://example.org/a") redirect("/b") else standard(url) }
        assertNotNull(rig.fetch("https://example.org/a"))
        assertEquals("https://example.org/b", rig.requests[1])
        assertEquals(listOf("example.org", "example.org", "cdn.example.net"), rig.resolved, "the name is resolved and checked on every hop")
    }

    @Test fun aRedirectToAnotherHostIsNotFollowed() {
        for (target in listOf("https://www.example.org/", "https://cdn.example.net/", "https://evil.example/", "http://example.org/", "https://127.0.0.1/", "//other.example/")) {
            val rig = Rig { redirect(target) }
            assertNull(rig.fetch("https://example.org/a"), target)
            assertEquals(listOf("https://example.org/a"), rig.requests, target)
        }
    }

    @Test fun aRedirectWithoutALocationOrWithAnOddStatusGivesNothing() {
        assertNull(Rig { PreviewResponse(302, null, null, null, null, false) }.fetch("https://example.org/a"))
        assertNull(Rig { PreviewResponse(300, "/b", null, null, null, false) }.fetch("https://example.org/a"))
        assertNull(Rig { PreviewResponse(304, "/b", null, null, null, false) }.fetch("https://example.org/a"))
    }

    @Test fun everyRedirectStatusIsFollowed() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            val rig = Rig { url -> if (url == "https://example.org/a") redirect("/b", status) else standard(url) }
            assertNotNull(rig.fetch("https://example.org/a"), status.toString())
        }
    }

    @Test fun aLoopOfRedirectsStopsAfterThree() {
        val rig = Rig { redirect("/again") }
        assertNull(rig.fetch("https://example.org/a"))
        assertEquals(4, rig.requests.size, "the first request and three redirects")
    }

    @Test fun aRedirectBackToAPrivateAddressByDnsRebindingIsRefused() {
        val rig = Rig { url ->
            if (url == "https://example.org/a") redirect("/b") else standard(url)
        }
        var calls = 0
        val tricky = LinkPreviewFetcher(
            resolver = { host -> calls++; listOf(InetAddress.getByName(if (calls == 1) "93.184.216.34" else "127.0.0.1")) },
            transport = { url, _, _, _, _ -> rig.requests += url; rig.handler(url) },
            shrinker = { null },
            nowMs = { 0 },
        )
        assertNull(runBlocking { tricky.fetch("https://example.org/a") })
        assertEquals(listOf("https://example.org/a"), rig.requests, "the second hop never connects")
    }

    // Size, type, status, time

    @Test fun aPageOverTheSizeCapIsRefused() {
        assertNull(Rig { PreviewResponse(200, null, "text/html", 10_485_760, null, true) }.fetch("https://example.org/a"))
        assertNull(Rig { PreviewResponse(200, null, "text/html", null, null, true) }.fetch("https://example.org/a"))
    }

    @Test fun aDeclaredLengthOverTheCapIsRefusedEvenWhenABodyCameBack() {
        assertNull(Rig { PreviewResponse(200, null, "text/html", 600_000, page, false) }.fetch("https://example.org/a"))
    }

    @Test fun aPageThatIsNotHtmlOrNotOkGivesNothing() {
        assertNull(Rig { PreviewResponse(200, null, "application/pdf", 10, page, false) }.fetch("https://example.org/a"))
        assertNull(Rig { PreviewResponse(200, null, null, 10, page, false) }.fetch("https://example.org/a"))
        assertNull(Rig { PreviewResponse(404, null, "text/html", 10, page, false) }.fetch("https://example.org/a"))
        assertNull(Rig { PreviewResponse(500, null, "text/html", 10, page, false) }.fetch("https://example.org/a"))
        assertNull(Rig { PreviewResponse(200, null, "text/html", 0, null, false) }.fetch("https://example.org/a"))
    }

    @Test fun xhtmlIsAccepted() {
        assertNotNull(Rig { PreviewResponse(200, null, "application/xhtml+xml", 10, page, false) }.fetch("https://example.org/a"))
    }

    @Test fun aPageWithoutATitleGivesNothing() {
        assertNull(Rig { html("<html><body>nothing</body></html>".encodeToByteArray()) }.fetch("https://example.org/a"))
    }

    @Test fun aTransportFailureGivesNothing() {
        assertNull(Rig { throw IOException("reset") }.fetch("https://example.org/a"))
        assertNull(Rig { throw IllegalStateException("anything") }.fetch("https://example.org/a"))
    }

    @Test fun theWholeFetchHasFiveSeconds() {
        // Time is spent by the page request: nothing is left for the image, and the preview comes without it.
        lateinit var rig: Rig
        rig = Rig { url -> if (url.endsWith("/i.png")) image() else { rig.elapsed = 5_000; html() } }
        val card = assertNotNull(rig.fetch("https://example.org/a"))
        assertNull(card.image)
        assertEquals(listOf("https://example.org/a"), rig.requests, "no image request after the time ran out")
        // Time already gone by the first redirect: no more requests.
        lateinit var slow: Rig
        slow = Rig { slow.elapsed = 5_000; redirect("/b") }
        assertNull(slow.fetch("https://example.org/a"))
        assertEquals(1, slow.requests.size)
    }

    // The image

    @Test fun aRelativeImageAddressIsResolvedAgainstThePage() {
        val body = """<title>t</title><meta property="og:image" content="/img/i.png">""".encodeToByteArray()
        val rig = Rig { url -> if (url.endsWith("/i.png")) image() else html(body) }
        assertNotNull(rig.fetch("https://example.org/a/b")!!.image)
        assertEquals("https://example.org/img/i.png", rig.requests[1])
    }

    @Test fun anImageOnAPrivateOrUnsafeHostIsNotFetchedButThePreviewStays() {
        for (img in listOf("https://evil.example/i.png", "http://cdn.example.net/i.png", "https://127.0.0.1/i.png", "https://[::1]/i.png", "https://localhost/i.png", "https://meta.example/i.png", "https://cdn.example.net:8443/i.png")) {
            val body = """<title>t</title><meta property="og:image" content="$img">""".encodeToByteArray()
            val rig = Rig { html(body) }
            val card = assertNotNull(rig.fetch("https://example.org/a"), img)
            assertNull(card.image, img)
            assertEquals(listOf("https://example.org/a"), rig.requests, img)
        }
    }

    @Test fun anImageRedirectToAnotherHostIsDropped() {
        val rig = Rig { url -> if (url.endsWith("/i.png")) redirect("https://example.org/x.png") else html() }
        assertNull(assertNotNull(rig.fetch("https://example.org/a")).image)
    }

    @Test fun anImageRedirectOnItsOwnHostIsFollowedAndChecked() {
        val rig = Rig { url ->
            when (url) {
                "https://cdn.example.net/i.png" -> redirect("/j.png")
                "https://cdn.example.net/j.png" -> image()
                else -> html()
            }
        }
        assertNotNull(rig.fetch("https://example.org/a")!!.image)
    }

    @Test fun anImageThatIsTooBigNotAnImageOrNotShrinkableIsDropped() {
        for (response in listOf(
            PreviewResponse(200, null, "image/png", 10_000_000, null, true),
            PreviewResponse(200, null, "text/html", 5, byteArrayOf(1), false),
            PreviewResponse(200, null, null, 5, byteArrayOf(1), false),
            PreviewResponse(404, null, "image/png", 5, byteArrayOf(1), false),
            PreviewResponse(200, null, "image/png", 600_000, byteArrayOf(1), false),
        )) {
            val rig = Rig { url -> if (url.endsWith("/i.png")) response else html() }
            assertNull(assertNotNull(rig.fetch("https://example.org/a")).image)
        }
        val rig = Rig(::standard)
        rig.shrunk = null
        assertNull(assertNotNull(rig.fetch("https://example.org/a")).image)
    }

    @Test fun aShrunkImageThatIsStillTooBigOrNotAJpegIsDropped() {
        for (bad in listOf(ByteArray(8_193).also { it[0] = -1; it[1] = -40 }, byteArrayOf(1, 2, 3), ByteArray(0))) {
            val rig = Rig(::standard)
            rig.shrunk = bad
            assertNull(assertNotNull(rig.fetch("https://example.org/a")).image)
        }
    }

    @Test fun aShrinkerThatThrowsDropsOnlyTheImage() {
        val rig = Rig(::standard)
        val fetcher = LinkPreviewFetcher({ listOf(InetAddress.getByName("93.184.216.34")) }, { url, _, _, _, _ -> rig.handler(url) }, { throw OutOfMemoryError() }, { 0 })
        assertNull(assertNotNull(runBlocking { fetcher.fetch("https://example.org/a") }).image)
    }

    @Test fun theCardIsAlwaysWithinWhatAReceiverAccepts() {
        val long = "x".repeat(900)
        val body = "<meta property=\"og:title\" content=\"$long\"><meta property=\"og:description\" content=\"$long\">".encodeToByteArray()
        val card = assertNotNull(Rig { html(body) }.fetch("https://example.org/a"))
        assertEquals(200, card.title.length)
        assertEquals(300, card.desc!!.length)
        assertTrue(org.example.app.core.chats.LinkPreviewRule.cardFor("https://example.org/a", card) != null)
    }
}
