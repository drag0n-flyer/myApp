package org.example.app.net.api

import kotlinx.coroutines.runBlocking
import org.example.app.contracts.GroupRoleWire
import org.example.app.contracts.ServerEvent
import org.example.app.contracts.ServerException
import org.example.app.contracts.membersEvent
import org.example.app.net.live.IncomingFrame
import org.example.app.net.live.decodeFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Server design, Groups: create, read, add, remove, set role, and the `members` inbox item. */
class GroupCallsTest : ApiTestBase() {
    private val api by lazy { DeliveryApiImpl(client, AttachmentsApi(client)) }

    @Test
    fun createGroupPostsKindAndAccounts() = runBlocking {
        reply(body = """{"conversationID":"c9","mode":"e2ee","epoch":0,"kind":"group"}""")
        val c = api.createGroup(listOf("a2", "a3"))
        val r = next()
        assertEquals("POST /v1/conversations", "${r.method} ${r.path}")
        assertEquals("""{"kind":"group","accounts":["a2","a3"]}""", r.body.readUtf8())
        assertEquals("c9", c.conversationId); assertEquals("e2ee", c.mode); assertEquals(0L, c.epoch)
    }

    @Test
    fun createGroupKeepsServerErrorCodes() = runBlocking {
        reply(code = 404, body = """{"error":{"code":"not_found","message":"x"}}""")
        reply(code = 409, body = """{"error":{"code":"group_full","message":"x"}}""")
        assertEquals("not_found", assertFailsWith<ServerException> { api.createGroup(listOf("a2")) }.code)
        assertEquals("group_full", assertFailsWith<ServerException> { api.createGroup(listOf("a2")) }.code)
    }

    @Test
    fun conversationReadsKindMembersAndVersion() = runBlocking {
        reply(body = """{"conversationID":"c9","mode":"server_readable","epoch":4,"kind":"group","membersVersion":7,
            "members":[{"account":"a1","role":"owner"},{"account":"a2","role":"admin"},{"account":"a3","role":"member"}]}""")
        val c = api.conversation("c9")
        assertEquals("GET /v1/conversations/c9", next().let { "${it.method} ${it.path}" })
        assertEquals("group", c.kind); assertEquals(7L, c.membersVersion); assertEquals(4L, c.epoch); assertEquals("server_readable", c.mode)
        assertEquals(
            listOf("a1" to GroupRoleWire.OWNER, "a2" to GroupRoleWire.ADMIN, "a3" to GroupRoleWire.MEMBER),
            c.members.map { it.account to it.role },
        )
    }

    @Test
    fun aDirectConversationReadsAsBefore() = runBlocking {
        reply(body = """{"conversationID":"c1","mode":"e2ee","epoch":3}""")
        val c = api.conversation("c1")
        assertEquals("direct", c.kind); assertNull(c.membersVersion); assertEquals(emptyList(), c.members)
    }

    @Test
    fun anUnknownRoleReadsAsMember() = runBlocking {
        reply(body = """{"conversationID":"c9","mode":"e2ee","epoch":0,"kind":"group","membersVersion":1,
            "members":[{"account":"a1","role":"owner"},{"account":"a2","role":"god"}]}""")
        assertEquals(listOf(GroupRoleWire.OWNER, GroupRoleWire.MEMBER), api.conversation("c9").members.map { it.role })
    }

    @Test
    fun aHostileMemberListIsRefused() = runBlocking {
        val many = (1..101).joinToString(",") { """{"account":"a$it","role":"member"}""" }
        reply(body = """{"conversationID":"c9","mode":"e2ee","epoch":0,"kind":"group","membersVersion":1,"members":[$many]}""")
        reply(body = """{"conversationID":"c9","mode":"e2ee","epoch":0,"kind":"group","membersVersion":1,"members":[{"account":"a1","role":"owner"},{"account":"a1","role":"member"}]}""")
        reply(body = """{"conversationID":"c9","mode":"e2ee","epoch":0,"kind":"group","membersVersion":-3,"members":[]}""")
        assertEquals("decoding", assertFailsWith<ServerException> { api.conversation("c9") }.code)
        assertEquals("decoding", assertFailsWith<ServerException> { api.conversation("c9") }.code)
        assertEquals("decoding", assertFailsWith<ServerException> { api.conversation("c9") }.code)
    }

    @Test
    fun addAccounts() = runBlocking {
        reply(body = """{"membersVersion":8,"deleted":false}""")
        val r = api.addAccounts("c9", listOf("a4", "a5"))
        val a = next()
        assertEquals("POST /v1/conversations/c9/accounts", "${a.method} ${a.path}")
        assertEquals("""{"accounts":["a4","a5"]}""", a.body.readUtf8())
        assertEquals(8L, r.membersVersion); assertEquals(false, r.deleted)
    }

    @Test
    fun removeAccountAndLeave() = runBlocking {
        reply(body = """{"membersVersion":9,"deleted":false}""")
        reply(body = """{"membersVersion":10,"deleted":true}""")
        assertEquals(9L, api.removeAccount("c9", "a4").membersVersion)
        val a = next()
        assertEquals("DELETE /v1/conversations/c9/accounts/a4", "${a.method} ${a.path}")
        assertEquals(true, api.removeAccount("c9", "a1").deleted)
    }

    @Test
    fun setRole() = runBlocking {
        reply(body = """{"membersVersion":11,"deleted":false}""")
        assertEquals(11L, api.setRole("c9", "a2", GroupRoleWire.ADMIN).membersVersion)
        val a = next()
        assertEquals("PUT /v1/conversations/c9/accounts/a2/role", "${a.method} ${a.path}")
        assertEquals("""{"role":"admin"}""", a.body.readUtf8())
    }

    @Test
    fun groupErrorCodesKeepTheirNames() = runBlocking {
        reply(code = 409, body = """{"error":{"code":"owner_must_transfer","message":"x"}}""")
        reply(code = 403, body = """{"error":{"code":"forbidden","message":"x"}}""")
        reply(code = 409, body = """{"error":{"code":"group_full","message":"x"}}""")
        reply(code = 404, body = """{"error":{"code":"not_found","message":"x"}}""")
        assertEquals("owner_must_transfer", assertFailsWith<ServerException> { api.removeAccount("c9", "a1") }.code)
        assertEquals("forbidden", assertFailsWith<ServerException> { api.removeAccount("c9", "a2") }.code)
        assertEquals("group_full", assertFailsWith<ServerException> { api.addAccounts("c9", listOf("a9")) }.code)
        assertEquals("not_found", assertFailsWith<ServerException> { api.setRole("c9", "a2", GroupRoleWire.MEMBER) }.code)
    }

    @Test
    fun aMembersInboxItemDecodesWithItsVersionAndAsAnEvent() = runBlocking {
        reply(body = """{"items":[
            {"cursor":5,"kind":"members","conversationID":"c9","epoch":2,"senderAccountID":"a2","senderDeviceID":"d2","payload":"","sentAt":"2026-11-18T10:00:00Z","membersVersion":12},
            {"cursor":6,"kind":"message","conversationID":"c9","seq":1,"epoch":2,"senderAccountID":"a2","senderDeviceID":"d2","payload":"AQL_","sentAt":"2026-11-18T10:00:01Z"}],
            "more":false}""")
        val page = api.inbox(4)
        val e = page.items[0].membersEvent()!!
        assertEquals("c9", e.conversationId); assertEquals(12L, e.membersVersion)
        assertNull(page.items[1].membersEvent())
        assertNull(page.items[1].membersVersion)
    }

    @Test
    fun aMembersItemWithoutAVersionOrOfAnotherKindIsNoEvent() = runBlocking {
        reply(body = """{"items":[
            {"cursor":5,"kind":"members","conversationID":"c9","epoch":2,"senderAccountID":"a2","senderDeviceID":"d2","payload":"","sentAt":"2026-11-18T10:00:00Z"},
            {"cursor":6,"kind":"commit","conversationID":"c9","epoch":2,"senderAccountID":"a2","senderDeviceID":"d2","payload":"","sentAt":"2026-11-18T10:00:00Z","membersVersion":3}],
            "more":false}""")
        val page = api.inbox(4)
        assertNull(page.items[0].membersEvent())
        assertNull(page.items[1].membersEvent())
    }

    @Test
    fun aLiveInboxFrameCarriesTheMembersVersion() {
        val frame = decodeFrame(
            """{"type":"inbox","data":{"cursor":5,"kind":"members","conversationID":"c9","epoch":2,"senderAccountID":"a2","senderDeviceID":"d2","payload":"","sentAt":"2026-11-18T10:00:00Z","membersVersion":12}}""",
        )
        val item = (assertIs<IncomingFrame.Event>(frame).event as ServerEvent.Inbox).item
        assertEquals(12L, item.membersEvent()!!.membersVersion)
    }
}
