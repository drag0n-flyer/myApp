package org.example.app.net.account

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.example.app.contracts.MemorySecretStore
import org.example.app.core.account.AccountError
import org.example.app.core.account.ServerAddress
import org.example.app.net.ServerClient
import org.example.app.net.Session
import org.example.app.net.SessionStore
import org.example.app.net.api.IdentityApi

private const val INFO3 = """{"protocol":1,"host":"chat.test","requiresInvite":true,
  "policy":{"defaultMode":"auto","maxMode":"balanced","defaultIPHiding":"nonContacts"},
  "encryption":{"newConversations":"e2ee","escrowKey":null},
  "push":{"gateway":null,"vapidKey":null},
  "features":["linking"],"federation":false,"limits":{"attachmentBytes":104857600}}"""

/** Server A (the first host) and server B (typed to `connect` later). */
private class SignUpRig {
    val a = FakeServer()
    val b = FakeServer()
    val hostA = a.web.url("/").let { "${it.host}:${it.port}" }
    val hostB = b.web.url("/").let { "${it.host}:${it.port}" }
    val sessions = SessionStore(MemorySecretStore())
    val clientA = ServerClient(a.web.url("/"), sessions, OkHttpClient())
    val clientB = ServerClient(b.web.url("/"), sessions, OkHttpClient())
    val service = LiveAccountService(
        client = clientA, sessionStore = sessions, passkeys = FakePasskeys(), hpke = ReferenceHpke(),
        snapshots = FakeSnapshots(), deviceName = "Pixel 8", biometrics = FakeApproval(),
        makeClient = { addr -> if (addr.hostPort == hostB) clientB else error("unexpected ${addr.hostPort}") },
        sleep = {},
    )

    init {
        a.json("POST /v1/invites/check", """{"valid":true}""")
        a.json("POST /v1/signup/start", """{"signupID":"SU-A"}""")
        b.json("GET /v1/server", INFO3)
        b.json("POST /v1/signup/start", """{"signupID":"SU-B"}""")
        b.json("POST /v1/signup/verify", "{}")
        a.json("POST /v1/link", """{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-10-28T12:05:00Z"}""")
        b.json("POST /v1/link/L1/claim", """{"accountID":"a1","deviceID":"d9","sessionToken":"tok-B","sealed":null}""")
    }

    fun shutdown() { a.shutdown(); b.shutdown() }
}

class FixRound3Test {
    private val toStop = ArrayList<() -> Unit>()
    @AfterTest fun tearDown() = toStop.forEach { it() }
    private fun rig() = SignUpRig().also { r -> toStop += { r.shutdown() } }

    // ---- N4: a loopback session keeps the scheme it was made over

    private fun bound(session: Session): ServerAddress? {
        var seen: ServerAddress? = null
        val sessions = SessionStore(MemorySecretStore()).also { it.save(session) }
        LiveAccountService(
            client = ServerClient("https://other.example/".toHttpUrl(), sessions, OkHttpClient()),
            sessionStore = sessions, passkeys = FakePasskeys(), hpke = ReferenceHpke(),
            snapshots = FakeSnapshots(), deviceName = "Pixel 8", biometrics = FakeApproval(),
            makeClient = { addr -> seen = addr; ServerClient("https://other.example/".toHttpUrl(), sessions, OkHttpClient()) },
            sleep = {},
        )
        return seen
    }

    @Test
    fun aLoopbackSessionIsReboundWithItsSavedScheme() {
        assertEquals(ServerAddress("localhost", 8443, true), bound(Session("localhost:8443", "a1", "d1", "tok", scheme = "https")))
        assertEquals(ServerAddress("localhost", 443, false), bound(Session("localhost:443", "a1", "d1", "tok", scheme = "http")))
        // A non-loopback host is https whatever was saved.
        assertEquals(ServerAddress("chat.example", null, true), bound(Session("chat.example", "a1", "d1", "tok", scheme = "http")))
    }

    @Test
    fun aSavedSessionRecordsTheSchemeOfItsClient() = runBlocking<Unit> {
        val f = Fixture()
        toStop += { f.shutdown() }
        f.serverInfo()
        f.server.json("POST /v1/signin/email/finish", f.newSession())
        f.service.connect(f.hostPort)
        f.service.recover("a@b.c", "123456")
        assertEquals("http", f.storedSession()!!.scheme)
    }

    // ---- N5: a link code is claimed only on the host that issued it

    @Test
    fun aLinkCodeIsNeverClaimedOnAnotherHost() = runBlocking<Unit> {
        val r = rig()
        val flow = LinkFlow(ReferenceHpke()) {}
        flow.start(IdentityApi(r.clientA), r.hostA, "Pixel 8", "android")
        assertFailsWith<AccountError.LinkExpired> { flow.awaitApproval(IdentityApi(r.clientB), r.hostB) }
        assertEquals(0, r.b.requests("POST /v1/link/L1/claim").size)
        assertTrue(r.b.seen.none { "C1" in it.body.peek().readUtf8() })
        // The ticket is gone: even the issuer cannot claim it now.
        assertFailsWith<AccountError.LinkExpired> { flow.awaitApproval(IdentityApi(r.clientA), r.hostA) }
    }

    // ---- N6: sign-up state belongs to the host it was made on

    @Test
    fun anInviteFromAIsNeverSentToB() = runBlocking<Unit> {
        val r = rig()
        assertTrue(r.service.redeemInvite("ABCDE-FGH23"))
        r.service.connect(r.hostB)
        r.service.startSignUp("Riya", "riya", "riya@example.org")
        assertFalse("ABCDE-FGH23" in r.b.bodyOf("POST /v1/signup/start"))
        assertFalse("inviteCode" in r.b.bodyOf("POST /v1/signup/start"))
    }

    @Test
    fun aSignUpFromAIsNeverVerifiedOnB() = runBlocking<Unit> {
        val r = rig()
        r.service.startSignUp("Riya", "riya", "riya@example.org")
        r.service.connect(r.hostB)
        assertFailsWith<AccountError.Server> { r.service.verifyEmail("123456") }
        assertEquals(0, r.b.requests("POST /v1/signup/verify").size)
    }
}
