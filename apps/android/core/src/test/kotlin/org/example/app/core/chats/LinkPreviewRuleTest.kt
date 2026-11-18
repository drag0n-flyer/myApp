package org.example.app.core.chats

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkPreviewRuleTest {
    private fun ip(text: String): ByteArray = InetAddress.getByName(text).address // literals only: no DNS

    // firstLink

    @Test fun firstHttpsLinkIsFound() {
        assertEquals("https://example.org/a?b=1", LinkPreviewRule.firstLink("look https://example.org/a?b=1 and https://other.example/"))
    }

    @Test fun firstLinkGivesNoLinkForUnsafeOrAbsentCases() {
        val none = listOf(
            "no link here",
            "http://example.org/",
            "see www.example.org",
            "mail mailto:a@example.org",
            "a `https://example.org` b",
            "```\nhttps://example.org\n```",
            "https://",
            "‮https://example.org", // direction control: the finder gives no links at all
        )
        for (text in none) assertNull(LinkPreviewRule.firstLink(text), text)
    }

    @Test fun anHttpLinkBeforeAnHttpsOneIsSkippedAndTheHttpsOneUsed() {
        assertEquals("https://b.example/", LinkPreviewRule.firstLink("http://a.example/ https://b.example/"))
    }

    @Test fun aLinkInsideCodeIsSkippedButALaterOneIsUsed() {
        assertEquals("https://b.example/", LinkPreviewRule.firstLink("`https://a.example/` then https://b.example/"))
    }

    @Test fun trailingPunctuationIsNotPartOfTheLink() {
        assertEquals("https://example.org/x", LinkPreviewRule.firstLink("go to https://example.org/x."))
    }

    @Test fun anUpperCaseSchemeCounts() {
        assertEquals("HTTPS://Example.org/", LinkPreviewRule.firstLink("HTTPS://Example.org/"))
    }

    @Test fun aLinkLongerThan2048CharactersGivesNone() {
        assertNull(LinkPreviewRule.firstLink("https://example.org/" + "a".repeat(2_050)))
        assertEquals(2_048, LinkPreviewRule.firstLink("https://example.org/" + "a".repeat(2_048 - 20))!!.length)
    }

    // allowedHost: names

    @Test fun publicNamesWithPublicAddressesAreAllowed() {
        assertTrue(LinkPreviewRule.allowedHost("example.org", listOf(ip("93.184.216.34"))))
        assertTrue(LinkPreviewRule.allowedHost("Example.ORG.", listOf(ip("93.184.216.34"), ip("2606:2800:220:1:248:1893:25c8:1946"))))
        assertTrue(LinkPreviewRule.allowedHost("xn--bcher-kva.example", listOf(ip("8.8.8.8"))))
    }

    @Test fun ipLiteralAndLocalNamesAreRefusedWhateverTheyResolveTo() {
        val publicAddress = listOf(ip("93.184.216.34"))
        val hosts = listOf(
            "127.0.0.1", "10.0.0.1", "169.254.169.254", "8.8.8.8", "[::1]", "::1", "[2606:2800:220:1::1]",
            "2130706433", "0x7f.0.0.1", "127.1", "1.2.3", "01.2.3.4",
            "localhost", "foo.localhost", "printer.local", "db.internal", "a.b.internal", "LOCALHOST", "nas.local.",
            "", ".", "example", "-a.example", "a-.example", "a..example", "exa mple.org", "exämple.org", "a_b.example",
            "a".repeat(64) + ".example", ("a.".repeat(130)) + "example",
        )
        for (host in hosts) assertFalse(LinkPreviewRule.allowedHost(host, publicAddress), host)
    }

    @Test fun aNameThatResolvesToNothingIsRefused() {
        assertFalse(LinkPreviewRule.allowedHost("example.org", emptyList()))
    }

    // allowedHost: addresses

    @Test fun privateLoopbackAndLinkLocalIPv4AreRefused() {
        val refused = listOf(
            "0.0.0.0", "0.1.2.3", "127.0.0.1", "127.255.255.254", "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255",
            "192.168.1.1", "169.254.169.254", "169.254.0.1", "100.64.0.1", "100.127.255.255", "224.0.0.1", "239.255.255.250",
            "240.0.0.1", "255.255.255.255", "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1",
        )
        for (a in refused) assertFalse(LinkPreviewRule.allowedHost("example.org", listOf(ip(a))), a)
    }

    @Test fun addressesJustOutsideTheRefusedRangesAreAllowed() {
        val fine = listOf("172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1", "11.0.0.1", "9.255.255.255", "169.253.1.1", "192.169.0.1", "126.255.255.255", "128.0.0.1")
        for (a in fine) assertTrue(LinkPreviewRule.allowedHost("example.org", listOf(ip(a))), a)
    }

    @Test fun privateLoopbackAndLinkLocalIPv6AreRefused() {
        val refused = listOf(
            "::", "::1", "fe80::1", "febf::1", "fc00::1", "fd12:3456::1", "fec0::1", "ff02::1", "ff00::",
            "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:192.168.1.1", "::ffff:169.254.169.254", // mapped IPv4
            "64:ff9b::7f00:1", "64:ff9b::a00:1", // NAT64 carrying a refused IPv4
            "2002:7f00:1::1", "2002:c0a8:101::1", // 6to4 carrying a refused IPv4
            "::7f00:1", // IPv4-compatible
            "2001:db8::1", "2001::1",
        )
        for (a in refused) assertFalse(LinkPreviewRule.allowedHost("example.org", listOf(ip(a))), a)
    }

    @Test fun publicIPv6IsAllowed() {
        assertTrue(LinkPreviewRule.allowedHost("example.org", listOf(ip("2606:4700:4700::1111"))))
        assertTrue(LinkPreviewRule.allowedHost("example.org", listOf(ip("::ffff:8.8.8.8"))))
        assertTrue(LinkPreviewRule.allowedHost("example.org", listOf(ip("64:ff9b::808:808"))))
    }

    @Test fun oneBadAddressAmongGoodOnesRefusesTheName() {
        assertFalse(LinkPreviewRule.allowedHost("example.org", listOf(ip("93.184.216.34"), ip("192.168.1.1"))))
        assertFalse(LinkPreviewRule.allowedHost("example.org", listOf(ip("192.168.1.1"), ip("93.184.216.34"))))
    }

    @Test fun anAddressOfTheWrongLengthIsRefused() {
        assertFalse(LinkPreviewRule.allowedHost("example.org", listOf(byteArrayOf(8, 8, 8))))
        assertFalse(LinkPreviewRule.allowedHost("example.org", listOf(ByteArray(0))))
    }

    // checkUrl

    @Test fun checkUrlAcceptsPublicHttpsUrlsAndGivesTheHost() {
        val ok = mapOf(
            "https://example.org/" to "example.org",
            "https://Example.ORG/a/b?c=d#e" to "example.org",
            "HTTPS://example.org:443/" to "example.org",
            "https://example.org." to "example.org",
            "https://sub.example.org/%C3%BC" to "sub.example.org",
        )
        for ((url, host) in ok) assertEquals(host, LinkPreviewRule.checkUrl(url), url)
    }

    @Test fun checkUrlRefusesEverythingElse() {
        val refused = listOf(
            "http://example.org/", "ftp://example.org/", "//example.org/", "/path", "example.org", "https:///a", "https://",
            "https://user@example.org/", "https://user:pw@example.org/", "https://example.org:8443/", "https://example.org:80/",
            "https://127.0.0.1/", "https://[::1]/", "https://10.0.0.1/", "https://169.254.169.254/latest/meta-data",
            "https://localhost/", "https://printer.local/", "https://db.internal/",
            "https://example.org/ a", "https://example.org/\nx", "https://exa mple.org/",
            "https://example.org/" + "a".repeat(2_100), "javascript:alert(1)", "",
        )
        for (url in refused) assertNull(LinkPreviewRule.checkUrl(url), url)
    }

    // redirectTarget

    @Test fun redirectsStayOnTheSameHostOverHttps() {
        val from = "https://example.org/a/b"
        assertEquals("https://example.org/b", LinkPreviewRule.redirectTarget(from, "/b"))
        assertEquals("https://example.org/a/c/d", LinkPreviewRule.redirectTarget(from, "c/d"))
        assertEquals("https://example.org/x?y=1", LinkPreviewRule.redirectTarget(from, "https://example.org/x?y=1"))
        assertEquals("https://EXAMPLE.org/x", LinkPreviewRule.redirectTarget(from, "https://EXAMPLE.org/x"))
        assertEquals("https://example.org/x", LinkPreviewRule.redirectTarget(from, "//example.org/x"))
    }

    @Test fun redirectsToAnotherHostOrAnUnsafeTargetAreRefused() {
        val from = "https://example.org/a"
        val refused = listOf(
            "https://other.example/", "https://www.example.org/", "https://sub.example.org/", "http://example.org/b",
            "//other.example/", "https://example.org:8443/", "https://user@example.org/", "https://127.0.0.1/", "https://[::1]/",
            "https://169.254.169.254/", "ftp://example.org/", "javascript:alert(1)", "", " ", "https://example.org/ a",
            "https://example.org/" + "a".repeat(2_100),
        )
        for (location in refused) assertNull(LinkPreviewRule.redirectTarget(from, location), location)
    }

    // cardFor: what may be sent

    private val jpeg = byteArrayOf(-1, -40, 1)

    @Test fun cardForKeepsAValidCardAndTrimsNothingElse() {
        val card = LinkPreviewCard("https://example.org/a", "Title", "Desc", jpeg)
        assertEquals(card, LinkPreviewRule.cardFor("see https://example.org/a", card))
        assertEquals(LinkPreviewCard("https://example.org/a", "T"), LinkPreviewRule.cardFor("https://example.org/a", LinkPreviewCard("https://example.org/a", "T")))
    }

    @Test fun cardForRefusesWhatAReceiverWouldDrop() {
        val text = "see https://example.org/a"
        val refused = listOf(
            LinkPreviewCard("https://example.org/b", "T"),
            LinkPreviewCard("http://example.org/a", "T"),
            LinkPreviewCard("https://example.org/a", ""),
            LinkPreviewCard("https://example.org/a", "  "),
            LinkPreviewCard("https://example.org/a", "t".repeat(201)),
            LinkPreviewCard("https://example.org/a", "T", "d".repeat(301)),
            LinkPreviewCard("https://example.org/a", "T", null, byteArrayOf(1, 2, 3)),
            LinkPreviewCard("https://example.org/a", "T", null, byteArrayOf(-1)),
            LinkPreviewCard("https://example.org/a", "T", null, ByteArray(8_193).also { it[0] = -1; it[1] = -40 }),
        )
        for (card in refused) assertNull(LinkPreviewRule.cardFor(text, card), card.title.length.toString() + card.url)
        assertNull(LinkPreviewRule.cardFor("see https://127.0.0.1/a", LinkPreviewCard("https://127.0.0.1/a", "T")))
    }

    @Test fun cardForCountsCodePointsAndAcceptsTheLimits() {
        val emoji = "\uD83D\uDE00"
        val text = "https://example.org/a"
        assertEquals(true, LinkPreviewRule.cardFor(text, LinkPreviewCard(text, emoji.repeat(200), emoji.repeat(300))) != null)
        assertNull(LinkPreviewRule.cardFor(text, LinkPreviewCard(text, emoji.repeat(201))))
        assertNull(LinkPreviewRule.cardFor(text, LinkPreviewCard(text, "T", emoji.repeat(301))))
        assertEquals(true, LinkPreviewRule.cardFor(text, LinkPreviewCard(text, "T", null, ByteArray(8_192).also { it[0] = -1; it[1] = -40 })) != null)
    }
}
