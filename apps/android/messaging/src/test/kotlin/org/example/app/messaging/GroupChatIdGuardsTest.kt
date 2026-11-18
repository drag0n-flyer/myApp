package org.example.app.messaging

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.example.app.core.chats.CallSummary
import org.example.app.messaging.ports.ChatRecord
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.SyncException
import org.example.app.messaging.sync.TestDevice

private class RecordingSocial : SocialPort {
    val blocked = mutableListOf<String>()
    override suspend fun lookUpUser(username: String) = null
    override suspend fun block(accountId: String) { blocked += accountId }
    override suspend fun unblock(accountId: String) = Unit
    override suspend fun report(accountId: String, conversationId: String?, messages: List<ReportedLine>) = Unit
}

/** Review Focus 5: a `g:` chat ID reaching code that assumes a contact does no harm and does the wrong thing never. */
class GroupChatIdGuardsTest {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val social = RecordingSocial()

    private fun store(d: TestDevice) = LiveChatStore(
        device = d.me, mls = d.mls, server = d.api, repo = d.repo, lock = d.lock, clock = d.clock, conversations = d.manager,
        inbox = d.processor, membership = d.membership, sealer = d.sealer(), escrow = d.escrow,
        readReceiptsEnabled = { d.readReceiptsSwitch() }, maxAttachmentBytes = 1234L,
        dirs = MediaDirs(tmp("blob"), tmp("open"), tmp("temp")), social = social, scope = scope, fold = { it.lowercase() },
        mediaSweep = { },
    )

    private fun tmp(n: String): File = Files.createTempDirectory(n).toFile().also { it.deleteOnExit() }

    private fun groupChat(d: TestDevice) = d.repo.upsertGroup("g:c1", "c1", "Book club", 5)

    @Test
    fun aGroupChatMapsWithNoContact() {
        val group = ChatMapping.chat(
            ChatRecord("g:c1", false, 0, false, 5, kind = "group", conversationId = "c1", title = "Book club"),
            emptyList(), null, emptySet(), emptyList(),
        )
        assertNull(group.contactId)
        assertEquals("Book club", group.title)
        val direct = ChatMapping.chat(ChatRecord("acc-ana", false, 0, false, 5), emptyList(), null, emptySet(), emptyList())
        assertEquals("acc-ana", direct.contactId)
        assertNull(direct.title)
    }

    @Test
    fun blockingAGroupIdIsRefusedAndTheGroupStays() = runTest {
        val rig = Rig().start()
        groupChat(rig.bob1)
        val s = store(rig.bob1)
        s.block("g:c1"); s.settle()
        assertTrue(s.blockedIds.value.isEmpty())
        assertTrue(rig.bob1.repo.blockedIds().isEmpty())
        assertEquals("group", rig.bob1.repo.chat("g:c1")?.kind)
        s.syncInbox()
        assertTrue(social.blocked.isEmpty())
        s.unblock("g:c1"); s.settle()
        assertTrue(rig.bob1.repo.entries("pending.").isEmpty())
    }

    @Test
    fun ensureChatForAGroupIdNeverMakesAContactOrADirectChat() = runTest {
        val rig = Rig().start()
        val s = store(rig.bob1)
        assertEquals("g:nope", s.ensureChat("g:nope"))
        assertNull(rig.bob1.repo.chat("g:nope"))
        assertNull(rig.bob1.repo.contact("g:nope"))
        groupChat(rig.bob1)
        assertEquals("g:c1", s.ensureChat("g:c1"))
        assertEquals("group", rig.bob1.repo.chat("g:c1")?.kind)
        assertNull(rig.bob1.repo.contact("g:c1"))
    }

    @Test
    fun aCallRecordedAgainstAGroupIdWritesNothing() = runTest {
        val rig = Rig().start()
        groupChat(rig.bob1)
        val s = store(rig.bob1)
        s.recordCall(CallSummary.MissedAudio, "g:c1", outgoing = false, at = Instant.ofEpochMilli(10))
        s.settle()
        assertTrue(rig.bob1.repo.calls(10).isEmpty())
        assertTrue(rig.bob1.repo.messages("g:c1", null, 10).isEmpty())
    }

    @Test
    fun noDirectConversationIsEverCreatedForAGroupId() = runTest {
        val rig = Rig().start()
        assertFailsWith<SyncException.NoPeer> { rig.bob1.manager.create("g:c1") }
        assertFailsWith<SyncException.NoPeer> { rig.bob1.manager.preferredConversation("g:c1") }
        assertTrue(rig.server.conversationIds().isEmpty())
        assertNull(rig.bob1.repo.chat("g:c1"))
    }
}
