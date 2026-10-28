package org.example.app.net.account

import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.example.app.contracts.MemorySecretStore
import org.example.app.core.account.AccountError
import org.example.app.core.account.DevicePlatform
import org.example.app.core.account.LinkCode
import org.example.app.core.account.LinkRequest
import org.example.app.core.account.ServerAddress
import org.example.app.net.Base64Url
import org.example.app.net.ServerClient
import org.example.app.net.Session
import org.example.app.net.SessionState
import org.example.app.net.SessionStore
import org.example.app.net.models.LinkTicket
import org.example.app.net.models.NewSession

private const val DEVICES = """{"devices":[{"id":"d1","name":"Pixel 8","platform":"android","createdAt":"2026-10-01T10:00:00Z","lastSeenAt":"2026-10-28T11:00:00Z","current":true}]}"""

/** Two servers: A is the default host the app's client points at, B is where the stored session lives. */
class TwoHostRig(wrongClient: Boolean = false) {
    val a = FakeServer()
    val b = FakeServer()
    val hostB = b.web.url("/").let { "${it.host}:${it.port}" }
    val sessions = SessionStore(MemorySecretStore()).also { it.save(Session(hostB, "a1", "d1", "tok-B")) }
    val clientA = ServerClient(a.web.url("/"), sessions, OkHttpClient())
    val clientB = ServerClient(b.web.url("/"), sessions, OkHttpClient())
    val approval = FakeApproval()
    val snapshots = FakeSnapshots()
    val hpke = ReferenceHpke()
    val service = LiveAccountService(
        client = clientA, sessionStore = sessions, passkeys = FakePasskeys(), hpke = hpke, snapshots = snapshots,
        deviceName = "Pixel 8", biometrics = approval,
        makeClient = { addr -> if (wrongClient) clientA else if (addr.hostPort == hostB) clientB else error("unexpected ${addr.hostPort}") },
        sleep = {},
    )
    fun shutdown() { a.shutdown(); b.shutdown() }
}

class FixRoundTest {
    private val toStop = ArrayList<() -> Unit>()
    @AfterTest fun tearDown() = toStop.forEach { it() }
    private fun twoHosts(wrong: Boolean = false) = TwoHostRig(wrong).also { r -> toStop += { r.shutdown() } }

    private val key32 = ByteArray(32) { it.toByte() }

    // ---- I1: the token goes only to the session's own host

    @Test
    fun aSessionOnACustomHostNeverSendsItsTokenToTheOtherHost() = runBlocking<Unit> {
        val r = twoHosts()
        r.b.json("GET /v1/devices", DEVICES)
        r.b.route("DELETE /v1/devices/d2") { MockResponse().setResponseCode(204) }
        r.b.route("DELETE /v1/me") { MockResponse().setResponseCode(204) }
        r.service.devices()
        r.service.removeDevice("d2")
        r.service.deleteAccount()
        assertEquals(0, r.a.seen.size, "nothing may reach the default host")
        assertTrue(r.b.seen.isNotEmpty() && r.b.seen.all { it.getHeader("Authorization") == "Bearer tok-B" })
    }

    @Test
    fun linkSecretAndTokenGoOnlyToTheSessionHost() = runBlocking<Unit> {
        val r = twoHosts()
        r.b.json("POST /v1/link/L1/info", """{"deviceName":"Pixel 9","platform":"android","createdAt":"2026-10-28T11:58:00Z"}""")
        r.b.route("POST /v1/link/L1/approve") { MockResponse().setResponseCode(204) }
        val qr = LinkQr.build(r.hostB, "L1", "S1", key32)
        val req = r.service.linkRequest(qr)
        r.service.approveLink(req)
        assertEquals(0, r.a.seen.size)
        assertEquals(2, r.b.seen.size)
    }

    @Test
    fun aClientForTheWrongHostIsRefusedAndSendsNothing() = runBlocking<Unit> {
        val r = twoHosts(wrong = true)
        assertFailsWith<AccountError.Server> { r.service.devices() }
        assertFailsWith<AccountError.Server> { r.service.removeDevice("d2") }
        assertFailsWith<AccountError.Server> { r.service.linkRequest(LinkQr.build(r.hostB, "L1", "S1", key32)) }
        assertFailsWith<AccountError.Server> { r.service.deleteAccount() }
        assertEquals(0, r.a.seen.size)
        assertEquals(0, r.b.seen.size)
    }

    // ---- I2: a busy secret store is not "signed out"

    @Test
    fun busyStoreAtLaunchIsUnavailableThenRestores() = runBlocking<Unit> {
        val store = BusyLoadStoreForService()
        val g = Fixture(store = store, signedIn = true)
        toStop += { g.shutdown() }
        store.failLoads = 1_000
        val service = LiveAccountService(
            g.client, SessionStore(store), g.passkeys, g.hpke, g.snapshots, "Pixel 8", g.approval,
            makeClient = { g.client }, sleep = {},
        )
        g.server.json("GET /v1/devices", DEVICES)
        // Not "no session": the UI shows a retry screen, not onboarding. Nothing is sent meanwhile.
        assertEquals(SessionState.Unavailable, service.sessionState)
        assertFailsWith<AccountError.Server> { service.devices() }
        assertEquals(0, g.server.seen.size)
        // The load is retried lazily; once the store answers, the session is back.
        store.failLoads = 0
        assertEquals(1, service.devices().size)
        assertTrue(service.isSignedIn)
        assertTrue(service.sessionState is SessionState.Present)
    }

    @Test
    fun whileUnavailableNothingIsSentAndTheStoredSessionIsNeverOverwritten() = runBlocking<Unit> {
        val store = BusyLoadStoreForService()
        val g = Fixture(store = store, signedIn = true)
        toStop += { g.shutdown() }
        val before = g.storedSession()
        store.failLoads = 1_000
        val sessions = SessionStore(store)
        val service = LiveAccountService(
            g.client, sessions, g.passkeys, g.hpke, g.snapshots, "Pixel 8", g.approval,
            makeClient = { g.client }, sleep = {},
        )
        g.server.json("POST /v1/signin/email/finish", g.newSession("evil-new"))
        assertEquals(SessionState.Unavailable, service.sessionState)
        assertFalse(service.isSignedIn)
        assertFailsWith<AccountError.Server> { service.recover("a@b.example", "123456") }
        assertFailsWith<AccountError.Server> { service.connect(g.hostPort) }
        assertFailsWith<AccountError.Server> { service.devices() }
        assertEquals(0, g.server.seen.size)
        store.failLoads = 0
        assertEquals(before, g.storedSession())
        assertEquals(0, store.writes - 1 /* the seed */)
    }

    // ---- concern 7: a claim is never lost to a busy save

    private fun linking(flaky: FlakyStore, claim: AtomicInteger, sealed: String? = null): Fixture {
        val g = Fixture(store = flaky)
        toStop += { g.shutdown() }
        g.server.json("POST /v1/link", """{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-10-28T12:05:00Z"}""")
        g.server.route("POST /v1/link/L1/claim") {
            claim.incrementAndGet()
            MockResponse().setBody("""{"accountID":"a1","deviceID":"d9","sessionToken":"link-token","sealed":${sealed?.let { "\"$it\"" }}}""")
        }
        return g
    }

    @Test
    fun aBusyStoreAfterTheClaimRetriesOnlyTheSave() = runBlocking<Unit> {
        val flaky = FlakyStore().also { it.failSaves = 1_000 }
        val claims = AtomicInteger()
        val g = linking(flaky, claims)
        g.service.makeLinkCode()
        assertFailsWith<AccountError.Server> { g.service.waitForLinkApproval() }
        assertFalse(g.service.isSignedIn)
        assertEquals(1, claims.get())
        assertTrue(g.sleeps.size >= 5, "the save is retried with backoff")
        assertTrue(g.sleeps.zipWithNext().all { (x, y) -> y >= x }, "backoff does not shrink")
        // "Try again": only the save is retried; the one-shot claim is never repeated.
        flaky.failSaves = 0
        val result = g.service.waitForLinkApproval()
        assertEquals(1, claims.get())
        assertTrue(g.service.isSignedIn)
        assertEquals("link-token", g.storedSession()!!.token)
        assertFalse(result.contactsCopied)
    }

    @Test
    fun theSessionWaitingForASaveIsDroppedWhenANewCodeIsMade() = runBlocking<Unit> {
        val flaky = FlakyStore().also { it.failSaves = 1_000 }
        val claims = AtomicInteger()
        val g = linking(flaky, claims)
        g.service.makeLinkCode()
        assertFailsWith<AccountError.Server> { g.service.waitForLinkApproval() }
        flaky.failSaves = 0
        g.service.makeLinkCode()
        g.service.waitForLinkApproval()
        assertEquals(2, claims.get()) // the new code was claimed, not the old session replayed
    }

    // ---- concern 3: no snapshot -> signed in, nothing imported, the UI is told

    @Test
    fun linkedWithoutContactsWhenSealedDoesNotOpen() = runBlocking<Unit> {
        val claims = AtomicInteger()
        val g = linking(FlakyStore(), claims, Base64Url.encode(ByteArray(120) { 7 }))
        g.service.makeLinkCode()
        val result = g.service.waitForLinkApproval()
        assertFalse(result.contactsCopied)
        assertEquals(0, g.snapshots.imports)
        assertTrue(g.service.isSignedIn)
    }

    @Test
    fun linkedWithContactsWhenSealedOpens() = runBlocking<Unit> {
        val hpke = ReferenceHpke()
        val pair = hpke.generateKeyPair()
        val g = Fixture(hpke = FixedKeyHpke(hpke, pair))
        toStop += { g.shutdown() }
        val sealed = Base64Url.encode(hpke.seal(pair.publicKey, org.example.app.contracts.HpkeInfo.LINK, """{"v":1}""".encodeToByteArray()))
        g.server.json("POST /v1/link", """{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-10-28T12:05:00Z"}""")
        g.server.json("POST /v1/link/L1/claim", """{"accountID":"a1","deviceID":"d9","sessionToken":"link-token","sealed":"$sealed"}""")
        g.service.makeLinkCode()
        assertTrue(g.service.waitForLinkApproval().contactsCopied)
        assertEquals(1, g.snapshots.imports)
    }

    @Test
    fun noSnapshotAtAllMeansContactsNotCopied() = runBlocking<Unit> {
        val g = linking(FlakyStore(), AtomicInteger(), null)
        g.service.makeLinkCode()
        assertFalse(g.service.waitForLinkApproval().contactsCopied)
    }

    // ---- concern 4: too large for the server -> approved without it, the approver is told

    @Test
    fun anOversizedSnapshotIsApprovedWithoutItAndTheApproverIsTold() = runBlocking<Unit> {
        val g = Fixture(signedIn = true)
        toStop += { g.shutdown() }
        g.server.route("POST /v1/link/L1/approve") { MockResponse().setResponseCode(204) }
        g.snapshots.data = ByteArray(64 * 1024 + 1)
        val req = LinkRequest(LinkQr.build(g.hostPort, "L1", "S1", g.hpke.generateKeyPair().publicKey), "Pixel 9", DevicePlatform.Android)
        val approval = g.service.approveLink(req)
        assertFalse(approval.snapshotSent)
        assertFalse("sealed" in g.bodyJson("POST /v1/link/L1/approve"))
    }

    @Test
    fun aSnapshotWithinTheLimitIsReportedAsSent() = runBlocking<Unit> {
        val g = Fixture(signedIn = true)
        toStop += { g.shutdown() }
        g.server.route("POST /v1/link/L1/approve") { MockResponse().setResponseCode(204) }
        val req = LinkRequest(LinkQr.build(g.hostPort, "L1", "S1", g.hpke.generateKeyPair().publicKey), "Pixel 9", DevicePlatform.Android)
        assertTrue(g.service.approveLink(req).snapshotSent)
    }

    // ---- minors

    private fun waiting(route: (Fixture) -> Unit): Fixture {
        val g = Fixture()
        toStop += { g.shutdown() }
        g.server.json("POST /v1/link", """{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-10-28T12:05:00Z"}""")
        route(g)
        return g
    }

    private val approvedJson = """{"accountID":"a1","deviceID":"d9","sessionToken":"link-token","sealed":null}"""

    @Test
    fun oneNetworkErrorOrServerErrorDoesNotEndLinking() = runBlocking<Unit> {
        val n = AtomicInteger()
        val g = waiting { f ->
            f.server.route("POST /v1/link/L1/claim") {
                when (n.incrementAndGet()) {
                    1 -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    2 -> MockResponse().setResponseCode(503).setBody("""{"error":{"code":"unavailable","message":"x"}}""")
                    3 -> MockResponse().setResponseCode(429).setBody("""{"error":{"code":"rate_limited","message":"x"}}""")
                    4 -> MockResponse().setResponseCode(202).setBody("{}")
                    else -> MockResponse().setBody(approvedJson)
                }
            }
        }
        g.service.makeLinkCode()
        g.service.waitForLinkApproval()
        // OkHttp may itself retry the dropped connection, so only a lower bound is stable.
        assertTrue(n.get() >= 5, "requests: ${n.get()}")
        assertTrue(g.service.isSignedIn)
        assertTrue(g.sleeps.size >= 3)
        assertTrue(g.sleeps.max() > g.sleeps.first(), "backoff grows across errors: ${g.sleeps}")
    }

    @Test
    fun forbiddenEndsLinking() = runBlocking<Unit> {
        val g = waiting { it.server.error("POST /v1/link/L1/claim", 403, "forbidden") }
        g.service.makeLinkCode()
        assertFailsWith<AccountError.LinkExpired> { g.service.waitForLinkApproval() }
        assertEquals(1, g.server.requests("POST /v1/link/L1/claim").size)
    }

    @Test
    fun theDeviceClockDoesNotEndLinking() = runBlocking<Unit> {
        // The code's expiry is already past by the device clock; only the server's answer ends the wait.
        val n = AtomicInteger()
        val g = waiting { f ->
            f.server.json("POST /v1/link", """{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2020-01-01T00:00:00Z"}""")
            f.server.route("POST /v1/link/L1/claim") {
                if (n.incrementAndGet() < 3) MockResponse().setResponseCode(202).setBody("{}") else MockResponse().setBody(approvedJson)
            }
        }
        g.service.makeLinkCode()
        g.service.waitForLinkApproval()
        assertEquals(3, n.get())
    }

    @Test
    fun aServerThatNeverAnswersEndsAtAFixedWaitNotTheClock() = runBlocking<Unit> {
        val g = waiting { it.server.json("POST /v1/link/L1/claim", "{}", 202) }
        g.service.makeLinkCode()
        assertFailsWith<AccountError.LinkExpired> { g.service.waitForLinkApproval() }
        assertTrue(g.sleeps.sum() in 5 * 60_000L..15 * 60_000L, "waited ${g.sleeps.sum()} ms")
    }

    @Test
    fun anUnknownPlatformIsOther() = runBlocking<Unit> {
        val g = Fixture(signedIn = true)
        toStop += { g.shutdown() }
        g.server.json("POST /v1/link/L1/info", """{"deviceName":"x","platform":"toaster","createdAt":"2026-10-28T11:58:00Z"}""")
        assertEquals(DevicePlatform.Other, g.service.linkRequest(LinkQr.build(g.hostPort, "L1", "S1", key32)).platform)
        g.server.json("GET /v1/devices", """{"devices":[{"id":"d2","name":"x","platform":"toaster","createdAt":"2026-10-01T10:00:00Z","lastSeenAt":null,"current":false}]}""")
        assertEquals(DevicePlatform.Other, g.service.devices().single().platform)
    }

    @Test
    fun secretsDoNotAppearInToString() {
        val ticket = LinkTicket("L1", "SECRET-S", "CLAIM-C", Instant.EPOCH)
        val qr = LinkQr.build("chat.test", "L1", "SECRET-S", key32)
        val texts = listOf(
            ticket.toString(),
            NewSession("a1", "d1", "TOKEN-T").toString(),
            Session("chat.test", "a1", "d1", "TOKEN-T").toString(),
            LinkCode(qr, Instant.EPOCH).toString(),
            LinkRequest(qr, "x", DevicePlatform.Android).toString(),
        )
        for (t in texts) {
            for (secret in listOf("SECRET-S", "CLAIM-C", "TOKEN-T", Base64Url.encode(key32))) assertFalse(secret in t, "$secret in $t")
        }
    }
}

class BusyLoadStoreForService(private val inner: org.example.app.contracts.SecretStore = MemorySecretStore()) :
    org.example.app.contracts.SecretStore by inner {
    var failLoads = 0
    var writes = 0
    override fun load(name: String): ByteArray? {
        if (failLoads > 0) { failLoads--; throw org.example.app.contracts.SecretStoreUnavailable("busy") }
        return inner.load(name)
    }
    override fun save(name: String, value: ByteArray) { writes++; inner.save(name, value) }
}
