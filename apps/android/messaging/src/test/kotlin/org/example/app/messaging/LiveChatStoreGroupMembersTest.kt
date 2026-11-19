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
        // Deleting a chat this account left removes the chat, its conversation rows and its members; a chat it is still in stays.
        val repo = w.device("cat1").repo
        cat.deleteChat(chat)
        cat.awaitWrites()
        assertEquals(null, repo.chat(chat))
        assertEquals(emptyList(), repo.conversations(chat))
        assertEquals(emptyList(), repo.groupMembers(chat))
        val bobRepo = w.device("bob1").repo
        bob.syncInbox(); bob.settle()
        bob.deleteChat(chat)
        bob.awaitWrites()
        assertTrue(bobRepo.chat(chat) != null, "still a member: not deleted")
    }

    @Test
    fun anAttachmentUploadingWhenIAmRemovedIsNeverSentAndTheMessageFails() = runTest {
        val w = GroupWorld.trio()
        val ana = store(w.device("ana1"))
        val chat = ana.createGroup(listOf("acc-bob", "acc-cat"), "Book club", null).chatId
        ana.settle()
        val bobDev = w.device("bob1")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val uploading = kotlinx.coroutines.CompletableDeferred<Unit>()
        val api = object : org.example.app.contracts.DeliveryApi by bobDev.api {
            override suspend fun uploadAttachment(bytes: java.io.InputStream, length: Long): org.example.app.contracts.AttachmentRef {
                bytes.readBytes()
                uploading.complete(Unit)
                gate.await()
                return org.example.app.contracts.AttachmentRef("up1", java.time.Instant.EPOCH)
            }
        }
        val bob = LiveChatStore(
            device = bobDev.me, mls = bobDev.mls, server = api, repo = bobDev.repo, lock = bobDev.lock, clock = bobDev.clock, conversations = bobDev.manager,
            inbox = bobDev.processor, membership = bobDev.membership, sealer = bobDev.sealer(), escrow = bobDev.escrow,
            readReceiptsEnabled = { true }, maxAttachmentBytes = 1234L,
            dirs = MediaDirs(tmp("blob"), tmp("open"), tmp("temp")),
            social = object : SocialPort {
                override suspend fun lookUpUser(username: String) = null
                override suspend fun block(accountId: String) = Unit
                override suspend fun unblock(accountId: String) = Unit
                override suspend fun report(accountId: String, conversationId: String?, messages: List<ReportedLine>) = Unit
            },
            scope = scope,
            mediaPreparer = MediaPreparer { path, _ -> PreparedMedia(File(path), 0, 0, null, 0.0, name = File(path).name) },
        )
        bob.syncInbox(); bob.settle()
        val src = File(tmp("src"), "notes.txt").also { it.writeText("hello there") }
        bob.sendAttachment(src.path, org.example.app.core.settings.MediaKind.File, chat)
        kotlinx.coroutines.withContext(Dispatchers.Default) { kotlinx.coroutines.withTimeout(10_000) { uploading.await() } }
        bob.leaveGroup(chat) // removed while the upload runs
        val before = w.server.messages.size
        gate.complete(Unit)
        bob.settle()
        val mine = bobDev.repo.messages(chat, null, 50).single { it.senderAccount == bobDev.me.accountId }
        assertEquals("failed", mine.status)
        assertEquals(before, w.server.messages.size, "nothing was sent after the removal")
        assertEquals(null, bobDev.repo.attachmentForMessage(mine.id), "the uploaded blob is not referenced")
    }
}
