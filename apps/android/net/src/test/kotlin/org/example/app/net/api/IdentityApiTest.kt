package org.example.app.net.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.app.contracts.ServerException
import org.example.app.net.models.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityApiTest : ApiTestBase() {
    private val api by lazy { IdentityApi(client) }
    private val session = """{"accountID":"a1","deviceID":"d1","sessionToken":"tok1"}"""

    @Test
    fun usernameAvailable() = runBlocking {
        reply(body = """{"available":true}""")
        assertTrue(api.usernameAvailable("ana_1"))
        val r = next()
        assertEquals("GET", r.method)
        assertEquals("/v1/usernames/ana_1", r.path)
        assertNull(r.getHeader("Authorization"))
    }

    @Test
    fun checkInvite() = runBlocking {
        reply(body = """{"valid":false}""")
        assertFalse(api.checkInvite("ABCDE-FGHJK"))
        val r = next()
        assertEquals("POST /v1/invites/check", "${r.method} ${r.path}")
        assertEquals("""{"code":"ABCDE-FGHJK"}""", r.body.readUtf8())
    }

    @Test
    fun signupStartBodyAndOmittedInvite() = runBlocking {
        reply(body = """{"signupID":"s1"}""")
        reply(body = """{"signupID":"s2"}""")
        assertEquals("s1", api.signupStart("ABCDE-FGHJK", "ana", "ana@example.org"))
        assertEquals("s2", api.signupStart(null, "ana", "ana@example.org"))
        val a = next()
        assertEquals("/v1/signup/start", a.path)
        assertEquals("""{"inviteCode":"ABCDE-FGHJK","username":"ana","email":"ana@example.org"}""", a.body.readUtf8())
        assertEquals("""{"username":"ana","email":"ana@example.org"}""", next().body.readUtf8())
    }

    @Test
    fun signupVerifyAndPasskeyOptions() = runBlocking {
        reply(body = "{}\n")
        reply(body = """{"publicKey":{"challenge":"abc","rp":{"id":"chat.test","name":"x"}}}""")
        api.signupVerify("s1", "123456")
        val opts = api.signupPasskeyOptions("s1")
        val v = next()
        assertEquals("/v1/signup/verify", v.path)
        assertEquals("""{"signupID":"s1","code":"123456"}""", v.body.readUtf8())
        val o = next()
        assertEquals("/v1/signup/passkey/options", o.path)
        assertEquals("""{"signupID":"s1"}""", o.body.readUtf8())
        assertEquals("abc", (opts["publicKey"] as JsonObject)["challenge"]!!.jsonPrimitive.content)
    }

    @Test
    fun signupFinishWithoutCredentialOmitsField() = runBlocking {
        reply(body = session)
        reply(body = session)
        val s = api.signupFinish("s1", null, "Pixel", "android")
        assertEquals(NewSession("a1", "d1", "tok1"), s)
        val a = next().json()
        assertEquals(setOf("signupID", "deviceName", "platform"), a.keys)
        assertEquals("android", a["platform"]!!.jsonPrimitive.content)
        api.signupFinish("s1", buildJsonObject { put("id", "c1") }, "Pixel", "android")
        val b = next().json()
        assertEquals("c1", (b["credential"] as JsonObject)["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun signinPasskey() = runBlocking {
        reply(body = """{"challengeID":"c9","options":{"publicKey":{"challenge":"zz","rpId":"chat.test"}}}""")
        reply(body = session)
        val o = api.signinPasskeyOptions()
        assertEquals("c9", o.challengeID)
        assertEquals("zz", ((o.options["publicKey"]) as JsonObject)["challenge"]!!.jsonPrimitive.content)
        val first = next()
        assertEquals("/v1/signin/passkey/options", first.path)
        assertEquals("{}", first.body.readUtf8())
        api.signinPasskeyFinish("c9", buildJsonObject { put("id", "c1") }, "Pixel", "android")
        val f = next()
        assertEquals("/v1/signin/passkey/finish", f.path)
        val j = f.json()
        assertEquals(setOf("challengeID", "credential", "deviceName", "platform"), j.keys)
    }

    @Test
    fun signinEmail() = runBlocking {
        reply(code = 202, body = "{}\n")
        reply(body = session)
        api.signinEmailStart("ana@example.org")
        val s = next()
        assertEquals("/v1/signin/email/start", s.path)
        assertEquals("""{"email":"ana@example.org"}""", s.body.readUtf8())
        assertEquals("tok1", api.signinEmailFinish("ana@example.org", "123456", "Pixel", "android").sessionToken)
        assertEquals(
            """{"email":"ana@example.org","code":"123456","deviceName":"Pixel","platform":"android"}""",
            next().body.readUtf8(),
        )
    }

    @Test
    fun addPasskey() = runBlocking {
        reply(body = """{"publicKey":{"challenge":"q"}}""")
        reply(body = "{}")
        api.addPasskeyOptions()
        api.addPasskey(buildJsonObject { put("id", "c1") })
        val o = next()
        assertEquals("/v1/me/passkeys/options", o.path)
        assertEquals("Bearer tok", o.getHeader("Authorization"))
        val p = next()
        assertEquals("POST /v1/me/passkeys", "${p.method} ${p.path}")
        assertEquals("""{"credential":{"id":"c1"}}""", p.body.readUtf8())
    }

    @Test
    fun meAndDeleteMe() = runBlocking {
        reply(body = """{"accountID":"a1","username":"ana","email":"ana@example.org","displayName":"Ana","findByEmail":true}""")
        reply(code = 204)
        val me = api.me()
        assertEquals("ana", me.username)
        assertTrue(me.findByEmail)
        assertEquals("GET /v1/me", next().let { "${it.method} ${it.path}" })
        api.deleteMe()
        assertEquals("DELETE /v1/me", next().let { "${it.method} ${it.path}" })
    }

    @Test
    fun devicesAndRevoke() = runBlocking {
        reply(body = """{"devices":[
            {"id":"d1","name":"Pixel","platform":"android","createdAt":"2026-10-28T10:00:00.123456789Z","lastSeenAt":"2026-10-28T11:00:00Z","current":true},
            {"id":"d2","name":"Mac","platform":"mac","createdAt":"2026-10-27T10:00:00Z","lastSeenAt":"0001-01-01T00:00:00Z","current":false}]}""")
        reply(code = 204)
        val d = api.devices()
        assertEquals(2, d.size)
        assertTrue(d[0].current)
        assertEquals(123456789, d[0].createdAt.nano)
        assertNull(d[1].lastSeenAt)
        assertEquals("/v1/devices", next().path)
        api.revokeDevice("d2")
        val r = next()
        assertEquals("DELETE /v1/devices/d2", "${r.method} ${r.path}")
    }

    @Test
    fun linkFlow() = runBlocking {
        reply(body = """{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-10-28T12:05:00.5Z"}""")
        reply(body = """{"deviceName":"Pixel","platform":"android","createdAt":"2026-10-28T12:00:00Z"}""")
        reply(code = 204)
        val t = api.linkStart("Pixel", "android")
        assertEquals("L1", t.linkID)
        assertEquals("C1", t.claimToken)
        val s = next()
        assertEquals("/v1/link", s.path)
        assertNull(s.getHeader("Authorization"))
        assertEquals("""{"deviceName":"Pixel","platform":"android"}""", s.body.readUtf8())
        assertEquals("Pixel", api.linkInfo("L1", "S1").deviceName)
        val i = next()
        assertEquals("/v1/link/L1/info", i.path)
        assertEquals("""{"secret":"S1"}""", i.body.readUtf8())
        api.linkApprove("L1", "S1", byteArrayOf(1, 2, 3, -1))
        val a = next()
        assertEquals("/v1/link/L1/approve", a.path)
        assertEquals("""{"secret":"S1","sealed":"AQID_w"}""", a.body.readUtf8())
    }

    @Test
    fun linkApproveWithoutSealed() = runBlocking {
        reply(code = 204)
        api.linkApprove("L1", "S1", null)
        assertEquals("""{"secret":"S1"}""", next().body.readUtf8())
    }

    @Test
    fun linkClaimPendingThenApproved() = runBlocking {
        reply(code = 202, body = "{}\n")
        reply(body = """{"accountID":"a1","deviceID":"d1","sessionToken":"tok1","sealed":"AQID_w"}""")
        reply(body = """{"accountID":"a1","deviceID":"d1","sessionToken":"tok1","sealed":null}""")
        assertIs<LinkClaim.Pending>(api.linkClaim("L1", "C1"))
        val a = assertIs<LinkClaim.Approved>(api.linkClaim("L1", "C1"))
        assertEquals("tok1", a.session.sessionToken)
        assertContentEquals(byteArrayOf(1, 2, 3, -1), a.sealed)
        assertNull(assertIs<LinkClaim.Approved>(api.linkClaim("L1", "C1")).sealed)
        val r = next()
        assertEquals("/v1/link/L1/claim", r.path)
        assertNull(r.getHeader("Authorization"))
        assertEquals("""{"claimToken":"C1"}""", r.body.readUtf8())
    }

    @Test
    fun errorsSurface() = runBlocking {
        reply(code = 410, body = """{"error":{"code":"expired","message":"x"}}""")
        assertEquals("expired", assertFailsWith<ServerException> { api.linkInfo("L1", "S1") }.code)
    }
}
