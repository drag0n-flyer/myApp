package org.example.app.core.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerAddressTest {
    private fun ok(text: String) = assertNotNull(ServerAddress.parse(text), text)

    @Test fun bareHostAndHostWithPortAreHttps() {
        assertEquals("home.example.org", ok("home.example.org").hostPort)
        assertEquals("home.example.org:8443", ok("home.example.org:8443").hostPort)
        assertTrue(ok("home.example.org").secure)
    }

    @Test fun httpsSchemeIsStrippedAndHostLowerCased() {
        val a = ok("  HTTPS://Home.Example.ORG:8443 ")
        assertEquals("home.example.org:8443", a.hostPort)
        assertEquals("home.example.org", a.host)
        assertEquals(8443, a.port)
    }

    @Test fun userinfoPathQueryFragmentAndSpacesAreRejected() {
        for (bad in listOf(
            "user@home.example.org", "https://user:pw@home.example.org", "home.example.org/", "home.example.org/path",
            "home.example.org?x=1", "home.example.org#frag", "home example.org", "home\nexample.org", "", "   ",
            "ftp://home.example.org", "https://", "-bad.example.org", "bad-.example.org", "a..b", "home.example.org.",
            "home.example.org:", "home.example.org:0", "home.example.org:65536", "home.example.org:abc", "a:1:2",
        )) assertNull(ServerAddress.parse(bad), bad)
    }

    @Test fun lengthIsCappedAt253() {
        val label = "a".repeat(63)
        val max = listOf(label, label, label, "a".repeat(61)).joinToString(".")
        assertEquals(253, max.length)
        assertNotNull(ServerAddress.parse(max))
        assertNull(ServerAddress.parse(max + "a"))
        assertNull(ServerAddress.parse("a".repeat(64) + ".example.org"))
    }

    @Test fun plainHttpIsOnlyForLoopback() {
        assertNull(ServerAddress.parse("http://home.example.org"))
        assertNull(ServerAddress.parse("http://10.0.2.2:8080"))
        assertNull(ServerAddress.parse("http://127.0.0.2"))
        for (loop in listOf("http://localhost:8080", "http://127.0.0.1", "http://[::1]:8080", "http://LOCALHOST")) {
            assertEquals(false, ok(loop).secure, loop)
        }
        assertTrue(ok("https://localhost").secure)
    }

    @Test fun ipv6NeedsBrackets() {
        assertEquals("[::1]:9000", ok("[::1]:9000").hostPort)
        assertEquals("[::1]", ok("::1").hostPort)
        assertNull(ServerAddress.parse("[::1"))
        assertNull(ServerAddress.parse("[::1]x"))
    }
}
