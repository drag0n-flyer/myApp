package org.example.app.messaging

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.example.app.core.chats.CallSummary
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.MessageContent
import org.example.app.messaging.content.Att
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.media.AttachmentSizeException
import org.example.app.messaging.ports.ChatRecord
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.ports.MessageRecord
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.TestDevice

private class FakeSocial : SocialPort {
    val blocked = mutableListOf<String>()
    var failBlock = false
    override suspend fun lookUpUser(username: String) = null
    override suspend fun block(accountId: String) { if (failBlock) error("offline"); blocked += accountId }
    override suspend fun report(accountId: String, conversationId: String?, messages: List<ReportedLine>) = Unit
}

class LiveChatStoreTest {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val social = FakeSocial()
    var swept = 0

    private fun store(d: TestDevice, fold: (String) -> String = { it.lowercase() }, max: Long = 1234L) = LiveChatStore(
        device = d.me, mls = d.mls, server = d.api, repo = d.repo, lock = d.lock, clock = d.clock, conversations = d.manager,
        inbox = d.processor, membership = d.membership, sealer = d.sealer(), escrow = d.escrow,
        readReceiptsEnabled = { d.readReceiptsSwitch() }, maxAttachmentBytes = max,
        dirs = MediaDirs(tmp("blob"), tmp("open"), tmp("temp")), social = social, scope = scope, fold = fold,
        mediaSweep = { swept += 1 },
    )

    private fun tmp(n: String): File = Files.createTempDirectory(n).toFile().also { it.deleteOnExit() }

    private suspend fun joined(): Triple<Rig, LiveChatStore, org.example.app.messaging.ports.ConversationRecord> {
        val rig = Rig().start()
        rig.bob1.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        val conv = rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        return Triple(rig, store(rig.bob1), conv)
    }

    @Test
    fun recordCallWritesOnce() = runTest {
        val (rig, s, _) = joined()
        val at = Instant.ofEpochMilli(NOW + 5)
        s.recordCall(CallSummary.Audio(75.0), "acc-ana", outgoing = false, at = at)
        s.recordCall(CallSummary.Audio(75.0), "acc-ana", outgoing = false, at = at) // a repeated report of the same call
        s.settle()
        val calls = rig.bob1.repo.calls(10)
        assertEquals(1, calls.size)
        assertEquals(1, rig.bob1.repo.messages("acc-ana", null, 50).count { it.id.startsWith("call-") })
        val line = s.chat("acc-ana")!!.messages.last()
        assertEquals(MessageContent.Call(CallSummary.Audio(75.0)), line.content)
        // A different call is another row.
        s.recordCall(CallSummary.MissedAudio, "acc-ana", false, Instant.ofEpochMilli(NOW + 9))
        s.settle()
        assertEquals(2, rig.bob1.repo.calls(10).size)
    }

    @Test
    fun strangerMissedCallIsRecordedOncePerCallAndNotWhenBlocked() = runTest {
        val rig = Rig().start()
        val s = store(rig.bob1)
        s.recordStrangerMissedCall("call-1", "acc-ana"); s.recordStrangerMissedCall("call-1", "acc-ana")
        assertEquals(1, rig.bob1.repo.calls(10).size)
        assertTrue(s.requests.value.single().isRequest)
        rig.bob1.repo.block("acc-eve", NOW)
        s.recordStrangerMissedCall("call-2", "acc-eve")
        assertEquals(1, rig.bob1.repo.calls(10).size)
    }

    @Test
    fun searchUsesFold() = runTest {
        val (rig, s, _) = joined()
        val fold: (String) -> String = { it.replace("é", "e").lowercase() }
        val folding = store(rig.bob1, fold)
        rig.bob1.repo.insertMessage(
            MessageRecord("m-s", "acc-ana", null, "acc-ana", "ana1", NOW, NOW, org.example.app.messaging.content.ContentCodec.encode(ContentEnvelope.Text("m-s", NOW, "Café au lait")), "delivered", folded = "cafe au lait"),
        )
        val hits = folding.searchMessages("  Café ", 10)
        assertEquals(listOf("m-s"), hits.map { it.messageId })
        assertEquals("Café au lait", hits.single().preview)
        assertTrue(s.searchMessages("   ", 10).isEmpty())
        assertTrue(s.searchMessages("Café", 10).isEmpty(), "the default fold does not strip accents: the injected one did")
    }

    @Test
    fun readTicksShowAsDeliveredWhileTheSwitchIsOff() = runTest {
        val (rig, s, _) = joined()
        val bob = rig.bob1
        bob.repo.insertMessage(MessageRecord("mine", "acc-ana", null, "acc-bob", "bob1", NOW, NOW, org.example.app.messaging.content.ContentCodec.encode(ContentEnvelope.Text("mine", NOW, "x")), "read"))
        bob.readReceiptsSwitch = { false }
        assertEquals(DeliveryStatus.Delivered, s.messages("acc-ana", null, 10).first { it.id == "mine" }.status)
        bob.readReceiptsSwitch = { true }
        assertEquals(DeliveryStatus.Read, s.messages("acc-ana", null, 10).first { it.id == "mine" }.status)
    }

    @Test
    fun oneSwitchGovernsOutboxAndReceipts() = runTest {
        val (rig, s, _) = joined()
        val bob = rig.bob1
        bob.repo.insertMessage(MessageRecord("in-1", "acc-ana", null, "acc-ana", "ana1", NOW, NOW, org.example.app.messaging.content.ContentCodec.encode(ContentEnvelope.Text("in-1", NOW, "x")), "delivered"))
        bob.repo.setUnread("acc-ana", 1)
        bob.readReceiptsSwitch = { false }
        s.markRead("acc-ana"); s.settle()
        assertTrue(bob.repo.dueOutbox(Long.MAX_VALUE).isEmpty(), "no read receipt queued")
        assertEquals("read", bob.repo.message("in-1")!!.status)
    }

    @Test
    fun sendAndReactAndEditAndDelete() = runTest {
        val (rig, s, _) = joined()
        s.send(MessageContent.Text("hello"), "acc-ana", null); s.settle()
        val mine = rig.bob1.repo.messages("acc-ana", null, 10).first { it.senderAccount == "acc-bob" }
        assertEquals("sent", mine.status)
        s.edit(mine.id, "acc-ana", "hello!"); s.settle()
        assertEquals(MessageContent.Text("hello!"), s.chat("acc-ana")!!.messages.first { it.id == mine.id }.content)
        s.delete(mine.id, "acc-ana"); s.settle()
        assertEquals(MessageContent.Deleted, s.chat("acc-ana")!!.messages.first { it.id == mine.id }.content)
        // Ana's device sees them after a sync.
        rig.ana1.processor.sync()
        assertTrue(rig.ana1.repo.message(mine.id)!!.deleted)
    }

    @Test
    fun blockIsLocalFirstAndRetried() = runTest {
        val (rig, s, _) = joined()
        social.failBlock = true
        s.block("acc-ana"); s.settle()
        assertTrue("acc-ana" in s.blockedIds.value)
        assertNull(s.chat("acc-ana"))
        assertTrue(social.blocked.isEmpty())
        social.failBlock = false
        s.syncInbox()
        assertEquals(listOf("acc-ana"), social.blocked)
    }

    @Test
    fun failedWelcomeStrangerIsNotShownAsARequest() = runTest {
        val rig = Rig().start()
        val bob = rig.bob1
        bob.repo.upsertChat(ChatRecord("acc-mallory", isRequest = true, unread = 0, muted = false, lastActivity = NOW))
        bob.repo.insertMessage(MessageRecord("join-failed-7", "acc-mallory", null, "acc-mallory", "d", NOW, NOW, """{"at":1,"id":"join-failed-7","k":"joinFailed","v":1}""".encodeToByteArray(), "delivered", undecryptable = true))
        val s = store(bob)
        assertTrue(s.requests.value.isEmpty())
        assertTrue(s.chats.value.isEmpty())
        // A real request from the same account (a conversation exists) is shown.
        bob.repo.insertMessage(MessageRecord("m-1", "acc-mallory", null, "acc-mallory", "d", NOW + 1, NOW, org.example.app.messaging.content.ContentCodec.encode(ContentEnvelope.Text("m-1", NOW, "hi")), "delivered"))
        s.refresh()
        assertEquals(1, s.requests.value.size)
    }

    @Test
    fun inactiveConversationIsNotRemoval() = runTest {
        val (rig, s, conv) = joined()
        rig.bob1.repo.upsertConversation(rig.bob1.repo.conversation(conv.serverId)!!.copy(active = false))
        s.refresh()
        val chat = assertNotNull(s.chat("acc-ana"))
        assertTrue(chat.messages.none { it.content is MessageContent.System })
        assertFalse(chat.isRequest)
        assertTrue(chat.messages.none { it.content is MessageContent.Deleted })
    }

    @Test
    fun resumePendingCreatesIsExposed() = runTest {
        val rig = Rig().start()
        val s = store(rig.ana1)
        s.resumePendingCreates() // nothing pending: returns
        assertTrue(rig.server.conversationIds().isEmpty())
    }

    @Test
    fun maxAttachmentBytesReachesTheDownloader() = runTest {
        val rig = Rig().start()
        val s = store(rig.bob1, max = 1000)
        assertEquals(1000, s.maxAttachmentBytes)
        val big = Att("att1", ByteArray(32), ByteArray(32), 2000, 2016)
        assertFailsWith<AttachmentSizeException> { s.downloader.download(big) }
    }

    @Test
    fun startupSweepsRunBoth() = runTest {
        val rig = Rig().start()
        val dirs = MediaDirs(tmp("blob"), tmp("open"), tmp("temp"))
        File(dirs.openDir, "leftover").writeText("plain")
        File(dirs.blobDir, "x.enc.part").writeText("partial")
        val s = LiveChatStore(
            rig.bob1.me, rig.bob1.mls, rig.bob1.api, rig.bob1.repo, rig.bob1.lock, rig.bob1.clock, rig.bob1.manager, rig.bob1.processor,
            rig.bob1.membership, rig.bob1.sealer(), null, { true }, 10, dirs, social, scope, mediaSweep = { swept += 1 },
        )
        s.sweepStartup()
        assertEquals(1, swept)
        assertFalse(File(dirs.openDir, "leftover").exists())
        assertFalse(File(dirs.blobDir, "x.enc.part").exists())
    }

    @Test
    fun incomingCallDecisions() = runTest {
        val (rig, s, _) = joined()
        assertEquals(org.example.app.core.chats.IncomingCallDecision.Ring, s.incomingCall("acc-ana", false))
        assertEquals(org.example.app.core.chats.IncomingCallDecision.SilentMissed, s.incomingCall("acc-stranger", true))
        s.settle()
        assertTrue(s.requests.value.any { it.id == "acc-stranger" })
        rig.bob1.repo.block("acc-ana", NOW)
        assertEquals(org.example.app.core.chats.IncomingCallDecision.SilentMissed, s.incomingCall("acc-ana", false))
    }
}
