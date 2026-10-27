package org.example.app.net.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.example.app.contracts.ServerException
import org.example.app.net.models.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeliveryApiTest : ApiTestBase() {
    private val api by lazy { DeliveryApiImpl(client) }

    @Test
    fun keyPackages() = runBlocking {
        reply(body = """{"count":2}""")
        reply(body = """{"count":7}""")
        assertEquals(2, api.uploadKeyPackages(listOf(byteArrayOf(1, 2), byteArrayOf(-1))))
        assertEquals(7, api.keyPackageCount())
        val u = next()
        assertEquals("POST /v1/keypackages", "${u.method} ${u.path}")
        assertEquals("""{"keyPackages":["AQI","_w"]}""", u.body.readUtf8())
        assertEquals("GET /v1/keypackages/count", next().let { "${it.method} ${it.path}" })
    }

    @Test
    fun claimMapsByDevice() = runBlocking {
        reply(body = """{"keyPackages":[{"deviceID":"d1","keyPackage":"AQI"},{"deviceID":"d2","keyPackage":"_w"}]}""")
        reply(body = """{"keyPackages":[]}""")
        val m = api.claimKeyPackages("acc2")
        assertEquals(setOf("d1", "d2"), m.keys)
        assertContentEquals(byteArrayOf(1, 2), m["d1"])
        assertContentEquals(byteArrayOf(-1), m["d2"])
        val a = next()
        assertEquals("POST /v1/keypackages/claim", "${a.method} ${a.path}")
        assertEquals("""{"accountID":"acc2"}""", a.body.readUtf8())
        api.claimKeyPackages("acc2", listOf("d9"))
        assertEquals("""{"accountID":"acc2","deviceIDs":["d9"]}""", next().body.readUtf8())
    }

    @Test
    fun conversations() = runBlocking {
        reply(body = """{"conversationID":"c1","mode":"e2ee","epoch":0}""")
        reply(body = """{"conversationID":"c1","mode":"server_readable","epoch":3}""")
        val c = api.createConversation("acc2")
        assertEquals("c1", c.conversationId)
        assertEquals("e2ee", c.mode)
        assertEquals(0L, c.epoch)
        val r = next()
        assertEquals("POST /v1/conversations", "${r.method} ${r.path}")
        assertEquals("""{"members":["acc2"]}""", r.body.readUtf8())
        val g = api.conversation("c1")
        assertEquals("server_readable", g.mode)
        assertEquals(3L, g.epoch)
        assertEquals("GET /v1/conversations/c1", next().let { "${it.method} ${it.path}" })
    }

    @Test
    fun postCommit() = runBlocking {
        reply(body = """{"epoch":4}""")
        reply(body = """{"epoch":5}""")
        assertEquals(4L, api.postCommit("c1", 3, byteArrayOf(1, 2), byteArrayOf(3), listOf("d2"), emptyList()))
        val a = next()
        assertEquals("POST /v1/conversations/c1/commits", "${a.method} ${a.path}")
        assertEquals("""{"epoch":3,"commit":"AQI","welcome":"Aw","addDevices":["d2"],"removeDevices":[]}""", a.body.readUtf8())
        api.postCommit("c1", 4, byteArrayOf(1), null, emptyList(), listOf("d2"))
        val b = next().json()
        assertFalse("welcome" in b)
        assertEquals("d2", (b["removeDevices"] as JsonArray)[0].jsonPrimitive.content)
    }

    @Test
    fun staleEpochCarriesCurrentEpoch() = runBlocking {
        reply(code = 409, body = """{"error":{"code":"stale_epoch","message":"x","details":{"currentEpoch":4}}}""")
        val e = assertFailsWith<ServerException> { api.postCommit("c1", 3, byteArrayOf(1), null, emptyList(), emptyList()) }
        assertEquals("stale_epoch", e.code)
        assertEquals(4L, e.details?.get("currentEpoch"))
    }

    @Test
    fun postMessageOmitsNullEscrow() = runBlocking {
        reply(body = """{"seq":12}""")
        reply(body = """{"seq":13}""")
        assertEquals(12L, api.postMessage("c1", 2, byteArrayOf(1, 2), null, "m-1"))
        val a = next()
        assertEquals("POST /v1/conversations/c1/messages", "${a.method} ${a.path}")
        assertEquals("""{"epoch":2,"ciphertext":"AQI","clientMsgID":"m-1"}""", a.body.readUtf8())
        api.postMessage("c1", 2, byteArrayOf(1, 2), byteArrayOf(-1), "m-2")
        assertEquals("""{"epoch":2,"ciphertext":"AQI","escrow":"_w","clientMsgID":"m-2"}""", next().body.readUtf8())
    }

    @Test
    fun inboxItemDecodes() = runBlocking {
        reply(body = """{"items":[
            {"cursor":99,"kind":"message","conversationID":"c1","seq":1,"epoch":2,"senderAccountID":"a2","senderDeviceID":"d2","payload":"AQL_","sentAt":"2026-10-28T10:00:00.123456789Z"},
            {"cursor":100,"kind":"commit","conversationID":"c1","epoch":2,"senderAccountID":"a2","senderDeviceID":"d2","payload":"","sentAt":"2026-10-28T10:00:01Z"}],
            "more":true}""")
        val p = api.inbox(98)
        assertTrue(p.more)
        val m = p.items[0]
        assertEquals(99L, m.cursor)
        assertEquals("message", m.kind)
        assertEquals(1L, m.seq)
        assertContentEquals(byteArrayOf(1, 2, -1), m.payload)
        assertEquals(Instant.parse("2026-10-28T10:00:00.123456789Z"), m.sentAt)
        assertEquals("a2", m.senderAccountId)
        assertEquals("d2", m.senderDeviceId)
        assertEquals("c1", m.conversationId)
        val c = p.items[1]
        assertNull(c.seq)
        assertEquals(0, c.payload.size)
    }

    @Test
    fun inboxQuery() = runBlocking {
        reply(body = """{"items":[],"more":false}""")
        reply(body = """{"items":[],"more":false}""")
        api.inbox(99, 100)
        assertEquals("/v1/inbox?after=99&limit=100", next().path)
        api.inbox(null)
        assertEquals("/v1/inbox?limit=100", next().path)
    }

    @Test
    fun ack() = runBlocking {
        reply(code = 204)
        api.ack(100)
        val r = next()
        assertEquals("POST /v1/inbox/ack", "${r.method} ${r.path}")
        assertEquals("""{"upTo":100}""", r.body.readUtf8())
    }

    @Test
    fun devices() = runBlocking {
        reply(body = """{"devices":[{"id":"d1","name":"Pixel","platform":"android","createdAt":"2026-10-28T10:00:00Z","lastSeenAt":"2026-10-28T10:00:00Z","current":true}]}""")
        assertEquals("d1", api.devices().single().id)
    }

    @Test
    fun attachmentsDelegate() = runBlocking {
        reply(body = """{"id":"att1","expiresAt":"2026-11-28T10:00:00Z"}""")
        reply(code = 206, body = "xyz")
        val ref = api.uploadAttachment(byteArrayOf(1, 2, 3).inputStream(), 3)
        assertEquals("att1", ref.id)
        assertEquals("xyz", api.downloadAttachment("att1", 5).readBytes().decodeToString())
    }
}
