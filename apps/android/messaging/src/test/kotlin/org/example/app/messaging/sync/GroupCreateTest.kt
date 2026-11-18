package org.example.app.messaging.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.ServerException
import org.example.app.messaging.ports.ChatKind
import org.example.app.messaging.ports.GroupMemberRecord
import org.example.app.messaging.testing.FakeDeliveryServer

/** Creating a group: the one commit with every device, the group envelope after it, and what the server may refuse. */
class GroupCreateTest {
    private class World(devices: Map<String, List<String>>) {
        val server = FakeDeliveryServer()
        val all = devices.flatMap { (account, ids) -> ids.map { TestDevice(server, account, it) } }
        fun device(id: String) = all.first { it.me.deviceId == id }
        suspend fun start() = apply {
            for (d in all) d.api.uploadKeyPackages(if (d.me.deviceId.startsWith("none")) emptyList() else d.mls.makeKeyPackages(3))
        }
    }

    private suspend fun trio() = World(
        mapOf("acc-ana" to listOf("ana1"), "acc-bob" to listOf("bob1", "bob2"), "acc-cat" to listOf("cat1", "cat2"), "acc-dan" to listOf("dan1", "dan2")),
    ).start()

    @Test
    fun threeMembersWithTwoDevicesEachAreAddedByOneCommitWithSixAdds() = runTest {
        val w = trio()
        val ana = w.device("ana1")
        val made = ana.groups.create(listOf("acc-bob", "acc-cat", "acc-dan"), "Book club", null)
        assertEquals("g:conv-0001", made.chatId)
        assertEquals(emptyList(), made.notAdded)
        assertEquals(listOf(Triple("acc-ana/ana1", "conv-0001", 0L)), w.server.commitPosts)
        assertEquals(
            listOf("acc-ana/ana1", "acc-bob/bob1", "acc-bob/bob2", "acc-cat/cat1", "acc-cat/cat2", "acc-dan/dan1", "acc-dan/dan2"),
            w.server.conversation("conv-0001")!!.members.toList(),
        )
        val chat = assertNotNull(ana.repo.chat(made.chatId))
        assertEquals(ChatKind.GROUP, chat.kind)
        assertEquals("Book club", chat.title)
        assertEquals(1, chat.groupVer)
        assertEquals(
            listOf(GroupMemberRecord("acc-ana", "owner"), GroupMemberRecord("acc-bob", "member"), GroupMemberRecord("acc-cat", "member"), GroupMemberRecord("acc-dan", "member")),
            ana.repo.groupMembers(made.chatId),
        )
        val conversation = assertNotNull(ana.repo.preferredConversation(made.chatId))
        assertTrue(conversation.active)
        assertEquals(1L, conversation.epoch)
    }

    @Test
    fun theGroupEnvelopeAndTheModeGoOutAfterTheCommit() = runTest {
        val w = trio()
        val ana = w.device("ana1")
        val made = ana.groups.create(listOf("acc-bob", "acc-cat"), "Book club", null)
        ana.outbox.flush()
        val calls = w.server.calls.filter { it.startsWith("acc-ana/ana1 post") }
        assertEquals("acc-ana/ana1 postCommit", calls.first())
        assertEquals(2, calls.drop(1).size, "the mode and the group envelope")
        assertTrue(ana.repo.dueOutbox(Long.MAX_VALUE).isEmpty())
        assertEquals(made.chatId, ana.repo.chats().single { it.kind == ChatKind.GROUP }.id)
    }

    @Test
    fun aMemberWithNoKeyPackagesIsLeftOutAndNamedTheOthersAreAdded() = runTest {
        val w = World(mapOf("acc-ana" to listOf("ana1"), "acc-bob" to listOf("bob1"), "acc-cat" to listOf("cat1"), "acc-dan" to listOf("none-dan"))).start()
        val ana = w.device("ana1")
        val made = ana.groups.create(listOf("acc-bob", "acc-cat", "acc-dan"), "Trio", null)
        assertEquals(listOf("acc-dan"), made.notAdded)
        assertEquals(listOf("acc-ana/ana1", "acc-bob/bob1", "acc-cat/cat1"), w.server.conversation("conv-0001")!!.members.toList())
        assertEquals(listOf("acc-ana", "acc-bob", "acc-cat"), ana.repo.groupMembers(made.chatId).map { it.accountId })
    }

    @Test
    fun anAccountTheServerSkipsIsNamedAndItsDevicesAreNotAdded() = runTest {
        val w = trio()
        w.server.groupSkipAccounts = setOf("acc-bob")
        val ana = w.device("ana1")
        val made = ana.groups.create(listOf("acc-bob", "acc-cat", "acc-dan"), "Skip", null)
        assertEquals(listOf("acc-bob"), made.notAdded)
        assertTrue(w.server.conversation("conv-0001")!!.members.none { it.startsWith("acc-bob/") })
        assertEquals(listOf("acc-ana", "acc-cat", "acc-dan"), ana.repo.groupMembers(made.chatId).map { it.accountId })
    }

    @Test
    fun noOneToAddIsRefusedBeforeAnyGroupExists() = runTest {
        val w = World(mapOf("acc-ana" to listOf("ana1"), "acc-bob" to listOf("none-bob"), "acc-cat" to listOf("none-cat"))).start()
        val ana = w.device("ana1")
        assertFailsWith<SyncException.NoKeyPackages> { ana.groups.create(listOf("acc-bob", "acc-cat"), "Nobody", null) }
        assertTrue(w.server.conversationIds().isEmpty())
        assertTrue(ana.repo.chats().isEmpty())
    }

    @Test
    fun serverRefusalsAreSurfacedAndLeaveNoChatBehind() = runTest {
        for ((status, code) in listOf(404 to "not_found", 409 to "group_full")) {
            val w = trio()
            w.server.onCall = { _, call -> if (call == "createGroup") throw ServerException(status, code, null, null) }
            val ana = w.device("ana1")
            val e = assertFailsWith<ServerException> { ana.groups.create(listOf("acc-bob", "acc-cat"), "Refused", null) }
            assertEquals(code, e.code)
            assertTrue(ana.repo.chats().isEmpty(), code)
            assertTrue(w.server.commitPosts.isEmpty(), code)
        }
    }

    @Test
    fun theInputIsCheckedBeforeAnyNetworkCall() = runTest {
        val w = trio()
        val ana = w.device("ana1")
        assertFailsWith<IllegalArgumentException> { ana.groups.create(listOf("acc-bob"), "Too few", null) }
        assertFailsWith<IllegalArgumentException> { ana.groups.create(listOf("acc-bob", "acc-bob"), "Twice", null) }
        assertFailsWith<IllegalArgumentException> { ana.groups.create(listOf("acc-bob", "acc-ana"), "Me", null) }
        assertFailsWith<IllegalArgumentException> { ana.groups.create(listOf("acc-bob", "acc-cat"), "   ", null) }
        assertFailsWith<IllegalArgumentException> { ana.groups.create(listOf("acc-bob", "acc-cat"), "x".repeat(51), null) }
        assertFailsWith<IllegalArgumentException> { ana.groups.create(listOf("acc-bob", "acc-cat"), "Photo", byteArrayOf(1, 2, 3, 4)) }
        assertFailsWith<IllegalArgumentException> { ana.groups.create((1..100).map { "acc-$it" }, "Big", null) }
        assertTrue(w.server.calls.none { !it.endsWith("uploadKeyPackages") }, "no network call: ${w.server.calls}")
        assertNull(ana.repo.chats().firstOrNull())
    }
}
