package org.example.app.net.api

import kotlinx.coroutines.runBlocking
import org.example.app.contracts.ServerException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Server design: `expiresAt` on message send, and `POST /v1/contact-links` and `/redeem`. */
class PeopleCallsTest : ApiTestBase() {
    private val delivery by lazy { DeliveryApiImpl(client, AttachmentsApi(client)) }
    private val social by lazy { SocialApi(client) }

    @Test
    fun postMessageWithExpirySendsRfc3339ExpiresAt() = runBlocking {
        reply(body = """{"seq":12}""")
        reply(body = """{"seq":13}""")
        // 2026-11-19T10:00:00.250Z
        val at = Instant.parse("2026-11-19T10:00:00.250Z").toEpochMilli()
        assertEquals(12L, delivery.postMessage("c1", 2, byteArrayOf(1, 2), null, "m-1", at))
        assertEquals(
            """{"epoch":2,"ciphertext":"AQI","clientMsgID":"m-1","expiresAt":"2026-11-19T10:00:00.250Z"}""",
            next().body.readUtf8(),
        )
        // No expiry: the body is exactly what it was before.
        delivery.postMessage("c1", 2, byteArrayOf(1, 2), null, "m-2", null)
        assertEquals("""{"epoch":2,"ciphertext":"AQI","clientMsgID":"m-2"}""", next().body.readUtf8())
    }

    @Test
    fun createContactLink() = runBlocking {
        reply(body = """{"token":"AAAAAAAAAAAAAAAAAAAAAA","expiresAt":"2026-11-25T10:00:00Z"}""")
        val link = social.createContactLink()
        val a = next()
        assertEquals("POST /v1/contact-links", "${a.method} ${a.path}")
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA", link.token)
        assertEquals(Instant.parse("2026-11-25T10:00:00Z"), link.expiresAt)
    }

    @Test
    fun tooManyLinks() = runBlocking {
        reply(code = 429, body = """{"error":{"code":"too_many","message":"x"}}""")
        val e = assertFailsWith<ServerException> { social.createContactLink() }
        assertEquals(429, e.status); assertEquals("too_many", e.code)
    }

    @Test
    fun redeemContactLink() = runBlocking {
        reply(body = """{"accountID":"a9","username":"zoe","displayName":"Zoe"}""")
        val who = social.redeemContactLink("AAAAAAAAAAAAAAAAAAAAAA")
        val a = next()
        assertEquals("POST /v1/contact-links/redeem", "${a.method} ${a.path}")
        assertEquals("""{"token":"AAAAAAAAAAAAAAAAAAAAAA"}""", a.body.readUtf8())
        assertEquals("a9", who.accountID); assertEquals("zoe", who.username); assertEquals("Zoe", who.displayName)
    }

    @Test
    fun redeemErrorsKeepTheirCodes() = runBlocking {
        reply(code = 409, body = """{"error":{"code":"own_link","message":"x"}}""")
        reply(code = 410, body = """{"error":{"code":"expired","message":"x"}}""")
        reply(code = 429, body = """{"error":{"code":"rate_limited","message":"x"}}""")
        assertEquals("own_link", assertFailsWith<ServerException> { social.redeemContactLink("t") }.code)
        assertEquals("expired", assertFailsWith<ServerException> { social.redeemContactLink("t") }.code)
        val limited = assertFailsWith<ServerException> { social.redeemContactLink("t") }
        assertEquals("rate_limited", limited.code); assertEquals(429, limited.status)
    }
}
