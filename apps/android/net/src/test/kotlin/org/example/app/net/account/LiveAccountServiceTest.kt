package org.example.app.net.account

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.app.contracts.SecretNames
import org.example.app.core.account.AccountError
import org.example.app.core.account.DevicePlatform
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference

class LiveAccountServiceTest {
    private val f = Fixture()

    @AfterTest
    fun tearDown() = f.shutdown()

    private fun signUpUpToVerify() = runBlocking<Unit> {
        f.serverInfo()
        f.server.json("POST /v1/signup/start", """{"signupID":"s1"}""")
        f.server.route("POST /v1/signup/verify") { okhttp3.mockwebserver.MockResponse().setResponseCode(204) }
        f.server.route("PATCH /v1/me/settings") { okhttp3.mockwebserver.MockResponse().setResponseCode(204) }
        f.service.connect(f.hostPort)
        f.service.startSignUp("Riya Sen", "riya", "riya@example.org")
        f.service.verifyEmail("123456")
    }

    @Test
    fun connectReadsServerPolicy() = runBlocking<Unit> {
        f.serverInfo()
        val p = f.service.connect(f.hostPort)
        assertEquals(ModePreference.Auto, p.defaultPreference)
        assertEquals(DataMode.Balanced, p.maximum)
        assertTrue(p.requiresInvite)
        assertEquals(p, f.service.currentServer)
    }

    @Test
    fun signUpWithoutPasskeyWhenUnsupported() = runBlocking<Unit> {
        f.passkeysSupported = false
        signUpUpToVerify()
        f.server.json("POST /v1/signup/finish", f.newSession())
        assertFalse(f.service.supportsPasskeys)
        f.service.finishSignUp()
        val body = f.bodyJson("POST /v1/signup/finish")
        assertFalse("credential" in body)
        assertTrue(f.server.requests("POST /v1/signup/passkey/options").isEmpty())
        assertTrue(f.service.isSignedIn)
    }

    @Test
    fun emailCodeSignUpNeverRegistersAPasskey() = runBlocking<Unit> {
        signUpUpToVerify()
        f.server.json("POST /v1/signup/finish", f.newSession())
        assertTrue(f.service.supportsPasskeys)
        f.service.finishSignUpWithEmailCode()
        assertFalse("credential" in f.bodyJson("POST /v1/signup/finish"))
        assertNull(f.passkeys.created)
        assertTrue(f.service.isSignedIn)
    }

    @Test
    fun signUpWithPasskey() = runBlocking<Unit> {
        signUpUpToVerify()
        val options = """{"challenge":"abc","rp":{"id":"chat.test","name":"x"},"user":{"id":"AQ","name":"riya"}}"""
        f.server.json("POST /v1/signup/passkey/options", options)
        f.server.json("POST /v1/signup/finish", f.newSession())
        f.service.finishSignUp()
        assertEquals(options, f.passkeys.created)
        val body = f.bodyJson("POST /v1/signup/finish")
        assertEquals("cred1", (body["credential"] as JsonObject)["id"]!!.jsonPrimitive.content)
        assertEquals("s1", body["signupID"]!!.jsonPrimitive.content)
        assertEquals("Pixel 8", body["deviceName"]!!.jsonPrimitive.content)
    }

    @Test
    fun passkeyCancelledStopsBeforeFinish() = runBlocking<Unit> {
        signUpUpToVerify()
        f.server.json("POST /v1/signup/passkey/options", "{}")
        f.passkeys.failWith = AccountError.Cancelled
        assertFailsWith<AccountError.Cancelled> { f.service.finishSignUp() }
        assertTrue(f.server.requests("POST /v1/signup/finish").isEmpty())
        assertFalse(f.service.isSignedIn)
    }

    @Test
    fun displayNameSentAfterSignupFinish() = runBlocking<Unit> {
        signUpUpToVerify()
        f.server.json("POST /v1/signup/finish", f.newSession())
        f.service.finishSignUpWithEmailCode()
        val patch = f.server.requests("PATCH /v1/me/settings").single()
        assertEquals("""{"displayName":"Riya Sen"}""", patch.body.readUtf8())
        assertEquals("Bearer tok-new", patch.getHeader("Authorization"))
        val start = f.bodyJson("POST /v1/signup/start")
        assertFalse("displayName" in start)
    }

    @Test
    fun failedDisplayNamePatchDoesNotFailSignUp() = runBlocking<Unit> {
        signUpUpToVerify()
        f.server.error("PATCH /v1/me/settings", 500, "internal")
        f.server.json("POST /v1/signup/finish", f.newSession())
        f.service.finishSignUpWithEmailCode()
        assertTrue(f.service.isSignedIn)
    }

    @Test
    fun sessionIsStoredOnlyInTheSecretStore() = runBlocking<Unit> {
        signUpUpToVerify()
        f.server.json("POST /v1/signup/finish", f.newSession("secret-token"))
        f.service.finishSignUpWithEmailCode()
        val s = assertNotNull(f.storedSession())
        assertEquals("secret-token", s.token)
        assertEquals(f.hostPort, s.host)
        assertEquals("a1", s.accountId)
    }

    @Test
    fun emailSignIn() = runBlocking<Unit> {
        f.serverInfo()
        f.server.json("POST /v1/signin/email/start", "{}", 202)
        f.server.json("POST /v1/signin/email/finish", f.newSession())
        f.service.connect(f.hostPort)
        f.service.sendRecoveryCode("riya@example.org")
        f.service.recover("riya@example.org", "654321")
        val body = f.bodyJson("POST /v1/signin/email/finish")
        assertEquals("654321", body["code"]!!.jsonPrimitive.content)
        assertTrue(f.service.isSignedIn)
        assertEquals("tok-new", f.storedSession()!!.token)
    }

    @Test
    fun badCodeMapsToCodeInvalid() = runBlocking<Unit> {
        f.serverInfo()
        f.server.error("POST /v1/signin/email/finish", 400, "code_invalid")
        f.service.connect(f.hostPort)
        assertFailsWith<AccountError.CodeInvalid> { f.service.recover("a@b.c", "000000") }
        assertFalse(f.service.isSignedIn)
    }

    @Test
    fun rateLimitCarriesRetryAfter() = runBlocking<Unit> {
        f.server.route("POST /v1/signin/email/start") {
            okhttp3.mockwebserver.MockResponse().setResponseCode(429).setHeader("Retry-After", "30")
                .setBody("""{"error":{"code":"rate_limited","message":"x"}}""")
        }
        val e = assertFailsWith<AccountError.TooManyAttempts> { f.service.sendRecoveryCode("a@b.c") }
        assertEquals(30, e.retryAfterSeconds)
    }

    @Test
    fun passkeySignIn() = runBlocking<Unit> {
        f.serverInfo()
        val options = """{"challenge":"c","rpId":"chat.test"}"""
        f.server.json("POST /v1/signin/passkey/options", """{"challengeID":"ch1","options":$options}""")
        f.server.json("POST /v1/signin/passkey/finish", f.newSession())
        f.service.connect(f.hostPort)
        f.service.signInWithPasskey()
        assertEquals(options, f.passkeys.asserted)
        assertEquals("ch1", f.bodyJson("POST /v1/signin/passkey/finish")["challengeID"]!!.jsonPrimitive.content)
        assertTrue(f.service.isSignedIn)
    }

    @Test
    fun passkeySignInUnsupportedSendsNothing() = runBlocking<Unit> {
        f.passkeysSupported = false
        f.serverInfo()
        f.service.connect(f.hostPort)
        assertFailsWith<AccountError.PasskeyUnavailable> { f.service.signInWithPasskey() }
        assertTrue(f.server.requests("POST /v1/signin/passkey/options").isEmpty())
    }

    @Test
    fun platformIsAndroid() = runBlocking<Unit> {
        f.serverInfo()
        f.server.json("POST /v1/signin/email/finish", f.newSession())
        f.server.json("POST /v1/signin/passkey/options", """{"challengeID":"c","options":{}}""")
        f.server.json("POST /v1/signin/passkey/finish", f.newSession())
        f.server.json("POST /v1/link", """{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-10-28T12:05:00Z"}""")
        f.service.connect(f.hostPort)
        f.service.recover("a@b.c", "1")
        f.service.signInWithPasskey()
        f.service.makeLinkCode()
        for (key in listOf("POST /v1/signin/email/finish", "POST /v1/signin/passkey/finish", "POST /v1/link")) {
            assertEquals("android", f.bodyJson(key)["platform"]!!.jsonPrimitive.content, key)
        }
        // sign-up
        val g = Fixture()
        try {
            g.serverInfo()
            g.routeSignUp()
            g.service.connect(g.hostPort)
            g.service.startSignUp("N", "riya", "a@b.c")
            g.service.verifyEmail("1")
            g.service.finishSignUpWithEmailCode()
            assertEquals("android", g.bodyJson("POST /v1/signup/finish")["platform"]!!.jsonPrimitive.content)
        } finally {
            g.shutdown()
        }
    }

    private fun Fixture.routeSignUp() {
        val g = this
        g.server.json("POST /v1/signup/start", """{"signupID":"s1"}""")
        g.server.route("POST /v1/signup/verify") { okhttp3.mockwebserver.MockResponse().setResponseCode(204) }
        g.server.route("PATCH /v1/me/settings") { okhttp3.mockwebserver.MockResponse().setResponseCode(204) }
        g.server.json("POST /v1/signup/finish", g.newSession())
    }

    @Test
    fun unsupportedProtocolStopsSignUp() = runBlocking<Unit> {
        f.serverInfo(protocol = 2)
        f.server.json("POST /v1/signup/start", """{"signupID":"s1"}""")
        assertFailsWith<AccountError.Server> { f.service.connect(f.hostPort) }
        assertTrue(f.service.updateRequired)
        assertFailsWith<AccountError.Server> { f.service.startSignUp("N", "riya", "a@b.c") }
        assertFailsWith<AccountError.Server> { f.service.sendRecoveryCode("a@b.c") }
        assertTrue(f.server.requests("POST /v1/signup/start").isEmpty())
        assertTrue(f.server.requests("POST /v1/signin/email/start").isEmpty())
    }

    @Test
    fun inviteIsSentWithSignupStart() = runBlocking<Unit> {
        f.serverInfo()
        f.server.json("POST /v1/invites/check", """{"valid":true}""")
        f.server.json("POST /v1/signup/start", """{"signupID":"s1"}""")
        f.service.connect(f.hostPort)
        assertTrue(f.service.redeemInvite("ABCDE-FGH23"))
        f.service.startSignUp("N", "riya", "a@b.c")
        assertEquals("ABCDE-FGH23", f.bodyJson("POST /v1/signup/start")["inviteCode"]!!.jsonPrimitive.content)
    }

    @Test
    fun invalidInviteThrows() = runBlocking<Unit> {
        f.server.json("POST /v1/invites/check", """{"valid":false}""")
        assertFailsWith<AccountError.InviteInvalid> { f.service.redeemInvite("nope") }
    }

    @Test
    fun serverErrorsMap() = runBlocking<Unit> {
        f.serverInfo()
        f.server.error("POST /v1/signup/start", 409, "username_taken")
        f.service.connect(f.hostPort)
        assertFailsWith<AccountError.UsernameTaken> { f.service.startSignUp("N", "riya", "a@b.c") }
        f.server.error("POST /v1/signup/start", 400, "invite_required")
        assertFailsWith<AccountError.InviteRequired> { f.service.startSignUp("N", "riya", "a@b.c") }
    }

    @Test
    fun connectToAnotherHostWhileSignedInIsWrongServer() = runBlocking<Unit> {
        val g = Fixture(signedIn = true)
        try {
            assertFailsWith<AccountError.WrongServer> { g.service.connect("other.example.org") }
            assertEquals(0, g.server.seen.size)
        } finally {
            g.shutdown()
        }
    }

    @Test
    fun invalidAddressIsRejected() = runBlocking<Unit> {
        assertFailsWith<AccountError.InvalidServerAddress> { f.service.connect("http://evil.example.org") }
        assertFailsWith<AccountError.InvalidServerAddress> { f.service.connect("a.example.org/path") }
    }

    @Test
    fun checkUsername() = runBlocking<Unit> {
        f.server.json("GET /v1/usernames/riya", """{"available":false}""")
        f.server.json("GET /v1/usernames/sam", """{"available":true}""")
        assertEquals(org.example.app.core.account.UsernameCheck.Taken, f.service.checkUsername("riya"))
        assertEquals(org.example.app.core.account.UsernameCheck.Available, f.service.checkUsername("sam"))
        assertEquals(org.example.app.core.account.UsernameCheck.Invalid, f.service.checkUsername("A"))
        assertEquals(org.example.app.core.account.UsernameCheck.Unknown, f.service.checkUsername("xyz"))
    }

    @Test
    fun secretStoreUnavailableDuringSaveKeepsStateAndDoesNotSignIn() = runBlocking<Unit> {
        val flaky = FlakyStore().also { it.failSaves = 10 }
        val g = Fixture(store = flaky)
        try {
            g.serverInfo()
            g.server.json("POST /v1/signin/email/finish", g.newSession())
            g.service.connect(g.hostPort)
            assertFailsWith<AccountError.Server> { g.service.recover("a@b.c", "1") }
            assertFalse(g.service.isSignedIn)
            assertNull(g.sessions.current)
        } finally {
            g.shutdown()
        }
    }

    @Test
    fun secretStoreRecoversOnRetryDuringSave() = runBlocking<Unit> {
        val flaky = FlakyStore().also { it.failSaves = 1 }
        val g = Fixture(store = flaky)
        try {
            g.serverInfo()
            g.server.json("POST /v1/signin/email/finish", g.newSession())
            g.service.connect(g.hostPort)
            g.service.recover("a@b.c", "1")
            assertTrue(g.service.isSignedIn)
            assertEquals(2, flaky.saves)
        } finally {
            g.shutdown()
        }
    }

    @Test
    fun devicesMapPlatformsAndLastActive() = runBlocking<Unit> {
        val g = Fixture(signedIn = true)
        try {
            g.server.json(
                "GET /v1/devices",
                """{"devices":[{"id":"d1","name":"Pixel 8","platform":"android","createdAt":"2026-10-01T10:00:00Z","lastSeenAt":"2026-10-28T11:00:00Z","current":true},
                  {"id":"d2","name":"iPhone","platform":"iphone","createdAt":"2026-10-02T10:00:00Z","lastSeenAt":"0001-01-01T00:00:00Z","current":false}]}""",
            )
            val d = g.service.devices()
            assertEquals(DevicePlatform.Android, d[0].platform)
            assertTrue(d[0].isThisDevice)
            assertEquals(Instant.parse("2026-10-28T11:00:00Z"), d[0].lastActive)
            assertEquals(Instant.parse("2026-10-02T10:00:00Z"), d[1].lastActive)
        } finally {
            g.shutdown()
        }
    }

    @Test
    fun removeDeviceAndDeleteAccount() = runBlocking<Unit> {
        val g = Fixture(signedIn = true)
        try {
            g.server.route("DELETE /v1/devices/d2") { okhttp3.mockwebserver.MockResponse().setResponseCode(204) }
            g.server.route("DELETE /v1/me") { okhttp3.mockwebserver.MockResponse().setResponseCode(204) }
            g.service.removeDevice("d2")
            assertTrue(g.service.isSignedIn)
            g.service.deleteAccount()
            assertFalse(g.service.isSignedIn)
            assertNull(g.store.load(SecretNames.SESSION))
        } finally {
            g.shutdown()
        }
    }

    @Test
    fun signOutLocallyClearsSession() {
        val g = Fixture(signedIn = true)
        try {
            assertTrue(g.service.isSignedIn)
            g.service.signOutLocally()
            assertFalse(g.service.isSignedIn)
            assertNull(g.store.load(SecretNames.SESSION))
        } finally {
            g.shutdown()
        }
    }

    @Test
    fun unauthorizedEndsTheSessionAndNotifies() = runBlocking<Unit> {
        val g = Fixture(signedIn = true)
        try {
            var notified = 0
            g.service.onSignedOut = { notified++ }
            g.server.error("GET /v1/devices", 401, "unauthorized")
            assertFailsWith<AccountError.Server> { g.service.devices() }
            assertFalse(g.service.isSignedIn)
            assertEquals(1, notified)
        } finally {
            g.shutdown()
        }
    }
}
