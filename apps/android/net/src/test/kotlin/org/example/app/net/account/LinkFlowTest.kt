package org.example.app.net.account

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.example.app.contracts.HpkeInfo
import org.example.app.contracts.SecretNames
import org.example.app.contracts.X25519KeyPair
import org.example.app.core.account.AccountError
import org.example.app.core.account.DevicePlatform
import org.example.app.net.Base64Url

class LinkFlowTest {
    private val f = Fixture(signedIn = true)

    @AfterTest
    fun tearDown() = f.shutdown()

    private val key32 = ByteArray(32) { it.toByte() }
    private fun qr(host: String = f.hostPort, id: String = "L1", secret: String = "S1", key: ByteArray = key32) =
        LinkQr.build(host, id, secret, key)

    private fun ticket(expires: String = "2026-10-28T12:05:00Z") = f.server.json(
        "POST /v1/link", """{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"$expires"}""",
    )

    // ---- QR

    @Test
    fun qrHasExactlyOneDot() {
        val text = LinkQr.build("chat.example.org", "L1", "S1", key32)
        val fragment = text.substringAfter('#')
        assertEquals(1, fragment.count { it == '.' })
        assertEquals("https://chat.example.org/link/L1#S1.${Base64Url.encode(key32)}", text)
    }

    @Test
    fun qrRoundTrip() {
        val p = LinkQr.parse(LinkQr.build("Chat.Example.org", "L1", "S1", key32), "chat.example.org")
        assertEquals("L1", p.linkId)
        assertEquals("S1", p.secret)
        assertContentEquals(key32, p.publicKey)
    }

    @Test
    fun qrOtherHostIsWrongServerEvenWhenMalformed() {
        assertFailsWith<AccountError.WrongServer> { LinkQr.parse(LinkQr.build("evil.example.org", "L1", "S1", key32), "chat.example.org") }
        assertFailsWith<AccountError.WrongServer> { LinkQr.parse("https://evil.example.org/garbage", "chat.example.org") }
        assertFailsWith<AccountError.WrongServer> { LinkQr.parse("https://chat.example.org@evil.example.org/link/L1#S1.x", "chat.example.org") }
        assertFailsWith<AccountError.WrongServer> { LinkQr.parse("https://chat.example.org:444/link/L1#S1.x", "chat.example.org") }
    }

    @Test
    fun qrMalformedIsLinkExpired() {
        val own = "chat.example.org"
        val good = Base64Url.encode(key32)
        for (bad in listOf(
            "not a url", "http://$own/link/L1#S1.$good", "https://$own/other/L1#S1.$good", "https://$own/link/L1",
            "https://$own/link/L1#S1", "https://$own/link/L1#S1.$good.extra", "https://$own/link/L1#.$good",
            "https://$own/link/L1#S1.", "https://$own/link/L1#S1.${Base64Url.encode(ByteArray(31))}",
            "https://$own/link/L1#S1.!!!", "https://$own/link/#S1.$good", "https://$own/link/L1/x#S1.$good",
        )) {
            assertFailsWith<AccountError.LinkExpired>(bad) { LinkQr.parse(bad, own) }
        }
    }

    // ---- approver

    @Test
    fun approveLinkRejectsOtherHost() = runBlocking<Unit> {
        assertFailsWith<AccountError.WrongServer> { f.service.linkRequest(qr(host = "other.example.org")) }
        val req = org.example.app.core.account.LinkRequest(qr(host = "other.example.org"), "x", DevicePlatform.Mac)
        assertFailsWith<AccountError.WrongServer> { f.service.approveLink(req) }
        assertEquals(0, f.server.seen.size)
        assertEquals(0, f.approval.asked)
        assertEquals(0, f.snapshots.exports)
    }

    @Test
    fun linkRequestShowsDeviceDetails() = runBlocking<Unit> {
        f.server.json("POST /v1/link/L1/info", """{"deviceName":"Pixel 9","platform":"android","createdAt":"2026-10-28T11:58:00.25Z"}""")
        val r = f.service.linkRequest(qr())
        assertEquals("Pixel 9", r.deviceName)
        assertEquals(DevicePlatform.Android, r.platform)
        assertEquals(Instant.parse("2026-10-28T11:58:00.25Z"), r.requestedAt)
        val req = f.server.requests("POST /v1/link/L1/info").single()
        assertEquals("""{"secret":"S1"}""", req.body.readUtf8())
        assertEquals("Bearer tok", req.getHeader("Authorization"))
    }

    @Test
    fun linkRequestExpiredOrForbiddenIsLinkExpired() = runBlocking<Unit> {
        f.server.error("POST /v1/link/L1/info", 410, "expired")
        assertFailsWith<AccountError.LinkExpired> { f.service.linkRequest(qr()) }
        f.server.error("POST /v1/link/L1/info", 403, "forbidden")
        assertFailsWith<AccountError.LinkExpired> { f.service.linkRequest(qr()) }
    }

    @Test
    fun linkApprovalRequiresBiometrics() = runBlocking<Unit> {
        f.approval.outcome = ApprovalOutcome.Unavailable
        val req = org.example.app.core.account.LinkRequest(qr(), "Pixel 9", DevicePlatform.Android)
        assertFailsWith<AccountError.BiometricsUnavailable> { f.service.approveLink(req) }
        assertTrue(f.server.requests("POST /v1/link/L1/approve").isEmpty())
        assertEquals(0, f.snapshots.exports)
        f.approval.outcome = ApprovalOutcome.Failed
        assertFailsWith<AccountError.BiometricsUnavailable> { f.service.approveLink(req) }
        f.approval.outcome = ApprovalOutcome.Cancelled
        assertFailsWith<AccountError.Cancelled> { f.service.approveLink(req) }
        assertTrue(f.server.requests("POST /v1/link/L1/approve").isEmpty())
    }

    @Test
    fun approveSealsSnapshotToTheQrKey() = runBlocking<Unit> {
        f.server.route("POST /v1/link/L1/approve") { MockResponse().setResponseCode(204) }
        val pair = f.hpke.generateKeyPair()
        f.snapshots.data = """{"v":1,"contacts":[]}""".encodeToByteArray()
        val req = org.example.app.core.account.LinkRequest(qr(key = pair.publicKey), "Pixel 9", DevicePlatform.Android)
        f.service.approveLink(req)
        assertEquals(1, f.approval.asked)
        val body = Json.parseToJsonElement(f.server.bodyOf("POST /v1/link/L1/approve")).jsonObject
        assertEquals("S1", body["secret"]!!.jsonPrimitive.content)
        val sealed = Base64Url.decode(body["sealed"]!!.jsonPrimitive.content)
        assertContentEquals(f.snapshots.data, f.hpke.open(pair, HpkeInfo.LINK, sealed))
        assertEquals(32 + f.snapshots.data.size + 16, sealed.size)
    }

    @Test
    fun approveRequiresSignIn() = runBlocking<Unit> {
        val g = Fixture(signedIn = false)
        try {
            assertFailsWith<AccountError.Server> { g.service.linkRequest(LinkQr.build(g.hostPort, "L1", "S1", key32)) }
            assertEquals(0, g.server.seen.size)
        } finally {
            g.shutdown()
        }
    }

    // ---- new device

    @Test
    fun makeLinkCodeShowsQrWithFreshKeyAndExpiry() = runBlocking<Unit> {
        val g = Fixture()
        try {
            g.server.json("POST /v1/link", """{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-10-28T12:05:00Z"}""")
            val code = g.service.makeLinkCode()
            assertEquals(Instant.parse("2026-10-28T12:05:00Z"), code.expiresAt)
            val parsed = LinkQr.parse(code.qrText, g.hostPort)
            assertEquals("S1", parsed.secret)
            assertEquals(32, parsed.publicKey.size)
            assertEquals("""{"deviceName":"Pixel 8","platform":"android"}""", g.server.bodyOf("POST /v1/link"))
            // The claim token and the public key never leave the device in the request.
            assertFalse(g.server.bodyOf("POST /v1/link").contains("C1"))
        } finally {
            g.shutdown()
        }
    }

    private fun newDevice(block: Fixture.() -> Unit): Fixture {
        val g = Fixture()
        g.server.json("POST /v1/link", """{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-10-28T12:05:00Z"}""")
        g.block()
        return g
    }

    private fun approved(sealed: String?) =
        """{"accountID":"a1","deviceID":"d9","sessionToken":"link-token","sealed":${sealed?.let { "\"$it\"" }}}"""

    @Test
    fun claimPollsEvery2sUntilApproved() = runBlocking<Unit> {
        val g = newDevice {
            val n = java.util.concurrent.atomic.AtomicInteger()
            server.route("POST /v1/link/L1/claim") {
                if (n.incrementAndGet() < 3) MockResponse().setResponseCode(202).setBody("{}\n")
                else MockResponse().setBody(approved(null))
            }
        }
        try {
            g.service.makeLinkCode()
            g.service.waitForLinkApproval()
            assertEquals(3, g.server.requests("POST /v1/link/L1/claim").size)
            assertEquals(listOf(2000L, 2000L), g.sleeps)
            assertEquals("""{"claimToken":"C1"}""", g.server.requests("POST /v1/link/L1/claim").first().body.readUtf8())
            assertNull(g.server.requests("POST /v1/link/L1/claim").first().getHeader("Authorization"))
            assertTrue(g.service.isSignedIn)
            val s = assertNotNull(g.storedSession())
            assertEquals("link-token", s.token)
            assertEquals("d9", s.deviceId)
            assertEquals(0, g.snapshots.imports)
        } finally {
            g.shutdown()
        }
    }

    @Test
    fun serverSaysExpired() = runBlocking<Unit> {
        val g = newDevice { server.error("POST /v1/link/L1/claim", 410, "expired") }
        try {
            g.service.makeLinkCode()
            assertFailsWith<AccountError.LinkExpired> { g.service.waitForLinkApproval() }
            // The ticket is forgotten: a second wait has nothing to claim.
            assertFailsWith<AccountError.LinkExpired> { g.service.waitForLinkApproval() }
            assertEquals(1, g.server.requests("POST /v1/link/L1/claim").size)
        } finally {
            g.shutdown()
        }
    }

    @Test
    fun waitWithoutCodeIsLinkExpired() = runBlocking<Unit> {
        assertFailsWith<AccountError.LinkExpired> { f.service.waitForLinkApproval() }
    }

    @Test
    fun sealedThatFailsImportsNothing() = runBlocking<Unit> {
        val garbage = Base64Url.encode(ByteArray(120) { 7 })
        val g = newDevice { server.json("POST /v1/link/L1/claim", approved(garbage)) }
        try {
            g.service.makeLinkCode()
            g.service.waitForLinkApproval()
            assertEquals(0, g.snapshots.imports)
            // The link still works: the account just starts without the snapshot.
            assertTrue(g.service.isSignedIn)
        } finally {
            g.shutdown()
        }
    }

    @Test
    fun sealedForAnotherKeyImportsNothing() = runBlocking<Unit> {
        val other = g0Hpke.generateKeyPair()
        val sealed = Base64Url.encode(g0Hpke.seal(other.publicKey, HpkeInfo.LINK, "{}".encodeToByteArray()))
        val g = newDevice { server.json("POST /v1/link/L1/claim", approved(sealed)) }
        try {
            g.service.makeLinkCode()
            g.service.waitForLinkApproval()
            assertEquals(0, g.snapshots.imports)
        } finally {
            g.shutdown()
        }
    }

    @Test
    fun sealedWithWrongInfoImportsNothing() = runBlocking<Unit> {
        val pair = g0Hpke.generateKeyPair()
        val sealed = Base64Url.encode(g0Hpke.seal(pair.publicKey, HpkeInfo.ESCROW, "{}".encodeToByteArray()))
        val g = Fixture(hpke = FixedKeyHpke(g0Hpke, pair))
        try {
            g.server.json("POST /v1/link", """{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-10-28T12:05:00Z"}""")
            g.server.json("POST /v1/link/L1/claim", approved(sealed))
            g.service.makeLinkCode()
            g.service.waitForLinkApproval()
            assertEquals(0, g.snapshots.imports)
        } finally {
            g.shutdown()
        }
    }

    @Test
    fun opensTheSharedLinkVector() = runBlocking<Unit> {
        val v = Json.parseToJsonElement(vectorFile().readText()).jsonObject
        assertEquals(1, v["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(HpkeInfo.LINK, v["info"]!!.jsonPrimitive.content)
        val priv = v["recipientPrivateKeyHex"]!!.jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val pub = Base64Url.decode(v["recipientPublicKeyB64"]!!.jsonPrimitive.content)
        val cases = v["cases"]!!.jsonArray
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val c = case.jsonObject
            val g = Fixture(hpke = FixedKeyHpke(ReferenceHpke(), X25519KeyPair(priv, pub)))
            try {
                g.server.json("POST /v1/link", """{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-10-28T12:05:00Z"}""")
                g.server.json("POST /v1/link/L1/claim", approved(c["sealedB64"]!!.jsonPrimitive.content))
                val code = g.service.makeLinkCode()
                assertContentEquals(pub, LinkQr.parse(code.qrText, g.hostPort).publicKey)
                g.service.waitForLinkApproval()
                assertEquals(c["plaintextJSON"]!!.jsonPrimitive.content, g.snapshots.imported!!.decodeToString())
            } finally {
                g.shutdown()
            }
        }
    }

    @Test
    fun linkKeyIsClearedAfterTheClaim() = runBlocking<Unit> {
        val spy = SpyHpke(ReferenceHpke())
        val g = Fixture(hpke = spy)
        try {
            g.server.json("POST /v1/link", """{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-10-28T12:05:00Z"}""")
            g.server.json("POST /v1/link/L1/claim", approved(null))
            g.service.makeLinkCode()
            g.service.waitForLinkApproval()
            assertTrue(spy.pairs.single().privateKey.all { it == 0.toByte() })
        } finally {
            g.shutdown()
        }
    }

    @Test
    fun sessionSaveRetriedWhenSecretStoreBusy() = runBlocking<Unit> {
        val flaky = FlakyStore().also { it.failSaves = 1 }
        val g = Fixture(store = flaky)
        try {
            g.server.json("POST /v1/link", """{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-10-28T12:05:00Z"}""")
            g.server.json("POST /v1/link/L1/claim", approved(null))
            g.service.makeLinkCode()
            g.service.waitForLinkApproval()
            assertTrue(g.service.isSignedIn)
            assertEquals("link-token", g.storedSession()!!.token)
        } finally {
            g.shutdown()
        }
    }

    private val g0Hpke = ReferenceHpke()

    private fun vectorFile(): java.io.File {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val f = java.io.File(dir, "docs/protocol/test-vectors/link-v1.json")
            if (f.exists()) return f
            dir = dir.parentFile
        }
        error("link-v1.json not found")
    }
}

/** Hands out one fixed key pair first (the vector's), then behaves like [inner]. */
class FixedKeyHpke(private val inner: org.example.app.contracts.Hpke, private val pair: X25519KeyPair) : org.example.app.contracts.Hpke {
    override fun seal(recipientPublicKey: ByteArray, info: String, plaintext: ByteArray) = inner.seal(recipientPublicKey, info, plaintext)
    override fun open(keyPair: X25519KeyPair, info: String, sealed: ByteArray) = inner.open(keyPair, info, sealed)
    override fun generateKeyPair() = X25519KeyPair(pair.privateKey.copyOf(), pair.publicKey.copyOf())
}

class SpyHpke(private val inner: org.example.app.contracts.Hpke) : org.example.app.contracts.Hpke by inner {
    val pairs = ArrayList<X25519KeyPair>()
    override fun generateKeyPair() = inner.generateKeyPair().also { pairs += it }
}
