package org.example.app.messaging

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.example.app.core.chats.GroupChangeFailed
import org.example.app.core.groups.GroupRole
import org.example.app.messaging.sync.GroupCreator
import org.example.app.messaging.sync.GroupWorld
import org.example.app.messaging.sync.TestDevice

/** The group membership calls and the follow-up work on the live store. */
class LiveChatStoreGroupMembersTest {
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
    fun aGroupWhoseModePostFailedAtCreationGetsItsEnvelopesAtTheNextFlushOrReconnect() = runTest {
        val w = GroupWorld.trio()
        val ana = w.device("ana1")
        val s = store(ana)
        w.server.failNextMessages = 1 // the mode post
        assertFailsWith<org.example.app.core.chats.GroupCreateFailed> { s.createGroup(listOf("acc-bob", "acc-cat"), "Book club", null) }
        assertTrue(ana.repo.get(GroupCreator.INIT_PREFIX + "conv-0001")?.isNotEmpty() == true, "the marker is still set")
        s.syncGroups() // a reconnect: its flush finishes the group
        s.settle()
        assertTrue(ana.repo.get(GroupCreator.INIT_PREFIX + "conv-0001")?.isEmpty() == true, "the marker is cleared")
        assertTrue(w.server.messages.size >= 2, "the mode and the group envelope went out: ${w.server.messages.size}")
    }

    @Test
    fun aMembersItemMakesTheOtherMembersStoreReadTheListAndTheLeaverSeesAReadOnlyChat() = runTest {
        val w = GroupWorld.trio()
        val ana = store(w.device("ana1"))
        val bob = store(w.device("bob1"))
        val chat = ana.createGroup(listOf("acc-bob", "acc-cat", "acc-dan"), "Book club", null).chatId
        ana.settle()
        bob.syncInbox()
        bob.settle()
        ana.removeMember(chat, "acc-dan")
        bob.syncInbox()
        bob.settle()
        assertEquals(listOf("acc-ana", "acc-bob", "acc-cat"), w.device("bob1").repo.groupMembers(chat).map { it.accountId })
        val cat = store(w.device("cat1"))
        cat.syncInbox(); cat.settle()
        assertEquals(false, cat.chats.value.single { it.id == chat }.readOnly)
        cat.leaveGroup(chat)
        assertEquals(true, cat.chats.value.single { it.id == chat }.readOnly)
        assertEquals(GroupChangeFailed.Reason.Forbidden, assertFailsWith<GroupChangeFailed> { cat.renameGroup(chat, "Mine") }.reason)
        assertEquals(GroupChangeFailed.Reason.OwnerMustTransfer, assertFailsWith<GroupChangeFailed> { ana.leaveGroup(chat) }.reason)
        ana.setRole(chat, "acc-bob", GroupRole.Owner)
        ana.leaveGroup(chat)
        assertEquals(true, ana.chats.value.single { it.id == chat }.readOnly)
    }
}
