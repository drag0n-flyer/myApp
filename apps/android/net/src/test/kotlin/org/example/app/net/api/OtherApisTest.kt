package org.example.app.net.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import org.example.app.contracts.ServerException
import org.example.app.net.models.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SocialApiTest : ApiTestBase() {
    private val api by lazy { SocialApi(client) }

    @Test
    fun lookUpAndDiscover() = runBlocking {
        reply(body = """{"accountID":"a2","username":"bob","displayName":"Bob"}""")
        reply(body = """{"accountID":"a3","username":"cy","displayName":""}""")
        assertEquals("a2", api.lookUpUser("bob").accountID)
        val g = next()
        assertEquals("GET /v1/users/bob", "${g.method} ${g.path}")
        assertEquals("cy", api.discover("cy@example.org").username)
        val d = next()
        assertEquals("POST /v1/discover/email", "${d.method} ${d.path}")
        assertEquals("""{"email":"cy@example.org"}""", d.body.readUtf8())
    }

    @Test
    fun notFoundMaps() = runBlocking {
        reply(code = 404, body = """{"error":{"code":"not_found","message":"x"}}""")
        assertEquals(404, assertFailsWith<ServerException> { api.lookUpUser("zed") }.status)
    }

    @Test
    fun updateSettingsOmitsUnset() = runBlocking {
        reply(code = 204)
        reply(code = 204)
        api.updateSettings(displayName = "Ana", findByEmail = null)
        val a = next()
        assertEquals("PATCH /v1/me/settings", "${a.method} ${a.path}")
        assertEquals("""{"displayName":"Ana"}""", a.body.readUtf8())
        api.updateSettings(null, true)
        assertEquals("""{"findByEmail":true}""", next().body.readUtf8())
    }

    @Test
    fun blocks() = runBlocking {
        reply(code = 204)
        reply(code = 204)
        reply(body = """{"accountIDs":["a2","a3"]}""")
        api.block("a2")
        api.unblock("a2")
        assertEquals(listOf("a2", "a3"), api.blocks())
        assertEquals("PUT /v1/blocks/a2", next().let { "${it.method} ${it.path}" })
        assertEquals("DELETE /v1/blocks/a2", next().let { "${it.method} ${it.path}" })
        assertEquals("GET /v1/blocks", next().let { "${it.method} ${it.path}" })
    }

    @Test
    fun report() = runBlocking {
        reply(code = 202)
        reply(code = 202)
        api.report("a2", "c1", "spam", listOf(ReportedMessage(Instant.parse("2026-10-28T10:00:00Z"), "hi")), "note")
        val r = next()
        assertEquals("POST /v1/reports", "${r.method} ${r.path}")
        assertEquals(
            """{"accountID":"a2","conversationID":"c1","reason":"spam","messages":[{"sentAt":"2026-10-28T10:00:00Z","text":"hi"}],"note":"note"}""",
            r.body.readUtf8(),
        )
        api.report("a2", null, "other", emptyList(), null)
        assertEquals("""{"accountID":"a2","reason":"other","messages":[]}""", next().body.readUtf8())
    }
}

class AttachmentsApiTest : ApiTestBase() {
    private val api by lazy { AttachmentsApi(client) }

    @Test
    fun uploadSendsRawBodyWithLength() = runBlocking {
        reply(body = """{"id":"att1","expiresAt":"2026-11-28T10:00:00.5Z"}""")
        val bytes = ByteArray(5000) { it.toByte() }
        val ref = api.upload(bytes.inputStream(), bytes.size.toLong())
        assertEquals("att1", ref.id)
        assertEquals(Instant.parse("2026-11-28T10:00:00.5Z"), ref.expiresAt)
        val r = next()
        assertEquals("POST /v1/attachments", "${r.method} ${r.path}")
        assertEquals("application/octet-stream", r.getHeader("Content-Type"))
        assertEquals("5000", r.getHeader("Content-Length"))
        assertNull(r.getHeader("Transfer-Encoding"))
        assertContentEquals(bytes, r.body.readByteArray())
    }

    @Test
    fun attachmentDownloadSendsRange() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 65552-65554/65555").setBody("abc"))
        val body = api.download("att1", 65552)
        assertEquals("abc", body.readBytes().decodeToString())
        val r = next()
        assertEquals("GET /v1/attachments/att1", "${r.method} ${r.path}")
        assertEquals("bytes=65552-", r.getHeader("Range"))
        assertEquals("Bearer tok", r.getHeader("Authorization"))
    }

    @Test
    fun downloadFromStartSendsNoRange() = runBlocking {
        reply(body = "all")
        assertEquals("all", api.download("att1", 0).readBytes().decodeToString())
        assertNull(next().getHeader("Range"))
    }

    @Test
    fun tooLargeMaps() = runBlocking {
        reply(code = 413, body = """{"error":{"code":"too_large","message":"x"}}""")
        assertEquals("too_large", assertFailsWith<ServerException> { api.upload(ByteArray(1).inputStream(), 1) }.code)
    }
}

class TurnAndPushApiTest : ApiTestBase() {
    @Test
    fun turnDecodes() = runBlocking {
        reply(body = """{"iceServers":[{"urls":["stun:t.example.org:3478","turn:t.example.org:3478?transport=udp","turn:t.example.org:3478?transport=tcp","turns:t.example.org:443?transport=tcp"],"username":"1790000000:ab12cd34","credential":"c3RhdGU="}],"ttl":43200}""")
        val t = TurnApi(client).turnCredentials()
        assertEquals(43200, t.ttl)
        assertEquals(4, t.iceServers.single().urls.size)
        assertEquals("1790000000:ab12cd34", t.iceServers.single().username)
        assertEquals("GET /v1/turn", next().let { "${it.method} ${it.path}" })
    }

    @Test
    fun callsDisabled() = runBlocking {
        reply(code = 503, body = """{"error":{"code":"calls_disabled","message":"x"}}""")
        assertEquals("calls_disabled", assertFailsWith<ServerException> { TurnApi(client).turnCredentials() }.code)
    }

    @Test
    fun pushTokenPaths() = runBlocking {
        val api = PushTokenApi(client)
        reply(code = 204)
        reply(code = 204)
        reply(code = 204)
        reply(code = 204)
        api.putPushToken("fcm", buildJsonObject { put("sealed", "AQI") })
        val a = next()
        assertEquals("PUT /v1/push/tokens/fcm", "${a.method} ${a.path}")
        assertEquals("""{"sealed":"AQI"}""", a.body.readUtf8())
        assertEquals("Bearer tok", a.getHeader("Authorization"))
        api.putPushToken("unifiedpush", buildJsonObject { put("sealed", "AQI") })
        assertEquals("/v1/push/tokens/unifiedpush", next().path)
        api.putPushToken("alert", buildJsonObject { put("sealed", "AQI") })
        assertEquals("/v1/push/tokens/alert", next().path)
        api.deletePushToken("fcm")
        val d = next()
        assertEquals("DELETE /v1/push/tokens/fcm", "${d.method} ${d.path}")
    }
}

class ServerInfoApiTest : ApiTestBase() {
    // The document in server design section 7.5 (what serverinfo.info writes).
    private val doc = """{"protocol":1,"host":"chat.example.org","requiresInvite":true,
        "policy":{"defaultMode":"auto","maxMode":"high","defaultIPHiding":"nonContacts"},
        "encryption":{"newConversations":"e2ee","escrowKey":null},
        "push":{"gateway":"https://push.example.org"},
        "features":["calls","video","linking"],"federation":false,
        "limits":{"attachmentBytes":104857600}}"""

    @Test
    fun decodesDocumentWithoutVapidKey() = runBlocking {
        reply(body = doc)
        val i = ServerInfoApi(client).serverInfo()
        assertEquals(1, i.protocol)
        assertEquals("chat.example.org", i.host)
        assertTrue(i.requiresInvite)
        assertEquals("auto", i.policy.defaultMode)
        assertEquals("high", i.policy.maxMode)
        assertEquals("nonContacts", i.policy.defaultIPHiding)
        assertEquals("e2ee", i.encryption.newConversations)
        assertNull(i.encryption.escrowKey)
        assertEquals("https://push.example.org", i.push.gateway)
        assertNull(i.push.vapidKey)
        assertEquals(listOf("calls", "video", "linking"), i.features)
        assertEquals(104857600L, i.limits.attachmentBytes)
        val r = next()
        assertEquals("GET /v1/server", "${r.method} ${r.path}")
        assertNull(r.getHeader("Authorization"))
    }

    @Test
    fun escrowKeyAndVapidKey() = runBlocking {
        reply(body = doc.replace("\"escrowKey\":null", "\"escrowKey\":\"AQL_\"").replace("\"gateway\":\"https://push.example.org\"", "\"gateway\":null,\"vapidKey\":\"BPk\""))
        val i = ServerInfoApi(client).serverInfo()
        assertContentEquals(byteArrayOf(1, 2, -1), i.encryption.escrowKey)
        assertNull(i.push.gateway)
        assertEquals("BPk", i.push.vapidKey)
    }

    @Test
    fun unknownFieldsIgnored() = runBlocking {
        reply(body = doc.replace("\"protocol\":1,", "\"protocol\":1,\"newThing\":{\"a\":1},").replace("\"federation\":false", "\"federation\":false,\"extra\":[1]"))
        assertEquals("chat.example.org", ServerInfoApi(client).serverInfo().host)
    }

    @Test
    fun protocol2RequiresUpdate() = runBlocking {
        reply(body = doc.replace("\"protocol\":1", "\"protocol\":2"))
        reply(body = doc)
        val api = ServerInfoApi(client)
        assertEquals(ProtocolResult.UpdateRequired, ProtocolCheck.check(api.serverInfo()))
        assertEquals(ProtocolResult.Supported, ProtocolCheck.check(api.serverInfo()))
        assertEquals(1, ProtocolCheck.MAX_PROTOCOL)
    }
}
