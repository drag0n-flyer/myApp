package org.example.app.net.account

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.example.app.contracts.MemorySecretStore
import org.example.app.core.account.AccountError
import org.example.app.core.account.AccountState
import org.example.app.core.account.ServerAddress
import org.example.app.net.ServerClient
import org.example.app.net.Session
import org.example.app.net.SessionStore

private const val INFO = """{"protocol":1,"host":"chat.test","requiresInvite":true,
  "policy":{"defaultMode":"auto","maxMode":"balanced","defaultIPHiding":"nonContacts"},
  "encryption":{"newConversations":"e2ee","escrowKey":null},
  "push":{"gateway":null,"vapidKey":null},
  "features":["linking"],"federation":false,"limits":{"attachmentBytes":104857600}}"""

/** Server A (where linking starts) and server B (typed to `connect` later), with a store that can be made busy. */
private class LinkRig {
    val a = FakeServer()
    val b = FakeServer()
    val hostA = a.web.url("/").let { "${it.host}:${it.port}" }
    val hostB = b.web.url("/").let { "${it.host}:${it.port}" }
    val inner = MemorySecretStore()
    val store = FlakyStore(inner)
    val sessions = SessionStore(store)
    val clientA = ServerClient(a.web.url("/"), sessions, OkHttpClient())
    val clientB = ServerClient(b.web.url("/"), sessions, OkHttpClient())
    val service = LiveAccountService(
        client = clientA, sessionStore = sessions, passkeys = FakePasskeys(), hpke = ReferenceHpke(),
        snapshots = FakeSnapshots(), deviceName = "Pixel 8", biometrics = FakeApproval(),
        makeClient = { addr -> if (addr.hostPort == hostB) clientB else error("unexpected ${addr.hostPort}") },
        sleep = {},
    )

    init {
        a.json("POST /v1/link", """{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-10-28T12:05:00Z"}""")
        a.json("POST /v1/link/L1/claim", """{"accountID":"a1","deviceID":"d9","sessionToken":"tok-A","sealed":null}""")
        b.json("GET /v1/server", INFO)
        b.json("GET /v1/devices", """{"devices":[]}""")
        b.json("POST /v1/link/L1/claim", """{"accountID":"a1","deviceID":"d9","sessionToken":"tok-B","sealed":null}""")
    }

    fun shutdown() { a.shutdown(); b.shutdown() }
}

class FixRound2Test {
    private val toStop = ArrayList<() -> Unit>()
    @AfterTest fun tearDown() = toStop.forEach { it() }
    private fun rig() = LinkRig().also { r -> toStop += { r.shutdown() } }

    // ---- N1: an unsaved link session stays bound to the host that issued it

    @Test
    fun anUnsavedLinkSessionIsNeverSavedUnderOrSentToALaterHost() = runBlocking<Unit> {
        val r = rig()
        r.store.failSaves = 1_000
        r.service.makeLinkCode()
        assertFailsWith<AccountError.Server> { r.service.waitForLinkApproval() }
        // The person goes back and picks another server, then returns to linking.
        r.service.connect(r.hostB)
        r.store.failSaves = 0
        assertFailsWith<AccountError.LinkExpired> { r.service.waitForLinkApproval() }
        assertFalse(r.service.isSignedIn)
        assertNull(r.sessions.current)
    }

    @Test
    fun aLinkTicketFromAIsNeverClaimedOnB() = runBlocking<Unit> {
        val r = rig()
        r.service.makeLinkCode()
        r.service.connect(r.hostB)
        assertFailsWith<AccountError.LinkExpired> { r.service.waitForLinkApproval() }
        assertEquals(0, r.b.requests("POST /v1/link/L1/claim").size)
        assertTrue(r.b.seen.none { "C1" in it.body.peek().readUtf8() })
    }

    @Test
    fun hostBNeverSeesATokenFromA() = runBlocking<Unit> {
        val r = rig()
        r.store.failSaves = 1_000
        r.service.makeLinkCode()
        assertFailsWith<AccountError.Server> { r.service.waitForLinkApproval() }
        r.service.connect(r.hostB)
        r.store.failSaves = 0
        runCatching { r.service.waitForLinkApproval() }
        runCatching { r.service.devices() }
        assertTrue(r.b.seen.none { it.getHeader("Authorization")?.contains("tok-A") == true })
        assertTrue(r.b.seen.none { it.path?.contains("claim") == true })
    }

    // ---- I2 follow-up: the state the app maps to a retry screen

    @Test
    fun accountStateTellsUnavailableFromSignedOut() = runBlocking<Unit> {
        val store = BusyLoadStoreForService()
        val g = Fixture(store = store, signedIn = true)
        toStop += { g.shutdown() }
        store.failLoads = 1_000
        val service = LiveAccountService(
            g.client, SessionStore(store), g.passkeys, g.hpke, g.snapshots, "Pixel 8", g.approval,
            makeClient = { g.client }, sleep = {},
        )
        assertEquals(AccountState.Unavailable, service.accountState)
        assertFalse(service.isSignedIn)
        store.failLoads = 0
        assertEquals(AccountState.SignedIn, service.accountState)
        service.signOutLocally()
        assertEquals(AccountState.SignedOut, service.accountState)
        assertEquals(AccountState.SignedOut, Fixture().also { toStop += { it.shutdown() } }.service.accountState)
    }

    // ---- N2 and N3: the rebind checks the scheme, and keeps loopback's port

    @Test
    fun aPlainHttpClientForANonLoopbackSessionHostIsRefused() = runBlocking<Unit> {
        val sessions = SessionStore(MemorySecretStore()).also { it.save(Session("chat.example", "a1", "d1", "tok")) }
        val http = ServerClient("http://chat.example/".toHttpUrl(), sessions, OkHttpClient())
        val other = ServerClient("https://other.example/".toHttpUrl(), sessions, OkHttpClient())
        val service = LiveAccountService(
            client = other, sessionStore = sessions, passkeys = FakePasskeys(), hpke = ReferenceHpke(),
            snapshots = FakeSnapshots(), deviceName = "Pixel 8", biometrics = FakeApproval(),
            makeClient = { http }, sleep = {},
        )
        assertFailsWith<AccountError.Server> { service.devices() }
    }

    @Test
    fun loopbackKeepsItsPortWhenRebinding() {
        fun bound(host: String): ServerAddress? {
            var seen: ServerAddress? = null
            val sessions = SessionStore(MemorySecretStore()).also { it.save(Session(host, "a1", "d1", "tok")) }
            LiveAccountService(
                client = ServerClient("https://other.example/".toHttpUrl(), sessions, OkHttpClient()),
                sessionStore = sessions, passkeys = FakePasskeys(), hpke = ReferenceHpke(),
                snapshots = FakeSnapshots(), deviceName = "Pixel 8", biometrics = FakeApproval(),
                makeClient = { addr -> seen = addr; ServerClient("https://other.example/".toHttpUrl(), sessions, OkHttpClient()) },
                sleep = {},
            )
            return seen
        }
        assertEquals(ServerAddress("localhost", 80, false), bound("localhost"))
        assertEquals(ServerAddress("localhost", 80, false), bound("localhost:80"))
        assertEquals(ServerAddress("127.0.0.1", 8080, false), bound("127.0.0.1:8080"))
        assertEquals(ServerAddress("localhost", 443, true), bound("localhost:443"))
        assertEquals(ServerAddress("chat.example", null, true), bound("chat.example"))
    }
}
