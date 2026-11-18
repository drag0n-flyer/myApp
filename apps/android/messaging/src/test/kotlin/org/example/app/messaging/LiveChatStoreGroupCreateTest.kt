package org.example.app.messaging

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.ServerException
import org.example.app.core.chats.GroupCreateFailed
import org.example.app.messaging.sync.GroupWorld
import org.example.app.messaging.sync.TestDevice

/** `ChatStore.createGroup` on the live store: the chat shows with its name, and every failure is a [GroupCreateFailed]. */
class LiveChatStoreGroupCreateTest {
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private fun tmp(n: String): File = Files.createTempDirectory(n).toFile().also { it.deleteOnExit() }

    private fun store(d: TestDevice) = LiveChatStore(
        device = d.me, mls = d.mls, server = d.api, repo = d.repo, lock = d.lock, clock = d.clock, conversations = d.manager,
        inbox = d.processor, membership = d.membership, sealer = d.sealer(), escrow = d.escrow,
        readReceiptsEnabled = { true }, maxAttachmentBytes = 1234L,
        dirs = MediaDirs(tmp("blob"), tmp("open"), tmp("temp")),
        social = object : SocialPort {
            override suspend fun lookUpUser(username: String) = null
            override suspend fun block(accountId: String) = Unit
            override suspend fun unblock(accountId: String) = Unit
            override suspend fun report(accountId: String, conversationId: String?, messages: List<ReportedLine>) = Unit
        },
        scope = scope,
    )

    @Test
    fun theNewGroupIsInTheChatListWithItsNameAndNoContact() = runTest {
        val w = GroupWorld.trio()
        val s = store(w.device("ana1"))
        val made = s.createGroup(listOf("acc-bob", "acc-cat"), "Book club", null)
        s.settle()
        val chat = s.chats.value.single { it.id == made.chatId }
        assertEquals("Book club", chat.title)
        assertNull(chat.contactId)
        assertTrue(chat.isGroup)
        assertEquals(emptyList(), made.notAdded)
    }

    @Test
    fun failuresAreMappedToReasons() = runTest {
        val w = GroupWorld.trio()
        val s = store(w.device("ana1"))
        for ((code, status, reason) in listOf(
            Triple("not_found", 404, GroupCreateFailed.Reason.NotFound), Triple("group_full", 409, GroupCreateFailed.Reason.Full),
        )) {
            w.server.onCall = { _, call -> if (call == "createGroup") throw ServerException(status, code, null, null) }
            assertEquals(reason, assertFailsWith<GroupCreateFailed> { s.createGroup(listOf("acc-bob", "acc-cat"), "X", null) }.reason)
        }
        w.server.onCall = null
        assertEquals(GroupCreateFailed.Reason.Other, assertFailsWith<GroupCreateFailed> { s.createGroup(listOf("acc-bob"), "X", null) }.reason)
        val none = GroupWorld(mapOf("acc-ana" to listOf("ana1"), "acc-bob" to listOf("none-bob"), "acc-cat" to listOf("none-cat"))).start()
        assertEquals(
            GroupCreateFailed.Reason.NobodyReachable,
            assertFailsWith<GroupCreateFailed> { store(none.device("ana1")).createGroup(listOf("acc-bob", "acc-cat"), "X", null) }.reason,
        )
    }

    @Test
    fun aGroupChatIdIsNeverMadeIntoAContact() = runTest {
        val w = GroupWorld.trio()
        val s = store(w.device("ana1"))
        val made = s.createGroup(listOf("acc-bob", "acc-cat"), "Trio", null)
        assertEquals(made.chatId, s.ensureChat(made.chatId))
        s.settle()
        assertTrue(w.device("ana1").repo.contact(made.chatId) == null)
    }

    @Test
    fun theChatCarriesTheMemberListAndTheGroupPhotoShowsThroughProfilesUnderTheChatId() = runTest {
        val w = GroupWorld.trio()
        val s = store(w.device("ana1"))
        val photo = TestJpeg.of()
        val made = s.createGroup(listOf("acc-bob", "acc-cat"), "Trio", photo)
        s.settle()
        val info = s.chats.value.single { it.id == made.chatId }.group!!
        assertEquals(setOf("acc-ana", "acc-bob", "acc-cat"), info.members.map { it.accountId }.toSet())
        assertEquals(org.example.app.core.groups.GroupRole.Owner, info.roleOf("acc-ana"))
        assertEquals(org.example.app.core.groups.MyGroupState.Member, info.myState)
        assertTrue(photo.contentEquals(s.profiles.value[made.chatId]?.photo))
    }

    @Test
    fun deleteChatRemovesAGroupOnlyOnceThePersonIsOutOfIt() = runTest {
        val w = GroupWorld.trio()
        val d = w.device("ana1")
        val s = store(d)
        val made = s.createGroup(listOf("acc-bob", "acc-cat"), "Trio", null)
        s.settle()
        s.deleteChat(made.chatId); s.settle()
        assertTrue(s.chats.value.any { it.id == made.chatId }, "still a member")
        d.repo.setMyState(made.chatId, org.example.app.messaging.ports.MyState.LEFT)
        s.setMuted(made.chatId, true); s.settle() // any write reloads the lists
        assertEquals(org.example.app.core.groups.MyGroupState.Left, s.chats.value.single { it.id == made.chatId }.group!!.myState)
        s.deleteChat(made.chatId); s.settle()
        assertTrue(s.chats.value.none { it.id == made.chatId })
        assertNull(d.repo.chat(made.chatId))
    }
}
