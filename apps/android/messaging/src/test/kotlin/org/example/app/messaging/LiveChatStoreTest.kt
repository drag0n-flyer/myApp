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
import kotlinx.coroutines.async
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
    val unblocked = mutableListOf<String>()
    var failUnblock = false
    override suspend fun lookUpUser(username: String) = null
    override suspend fun block(accountId: String) { if (failBlock) error("offline"); blocked += accountId }
    override suspend fun unblock(accountId: String) { if (failUnblock) error("offline"); unblocked += accountId }
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
        // The Outbox asks the same switch when a queued read receipt is about to go out: off drops it, on sends it.
        val receipt = { ContentEnvelope.Receipt("r", NOW, org.example.app.messaging.content.ReceiptKind.Read, listOf("in-1")) }
        val posted = rig.server.messages.size
        s.outbox.enqueueControl("acc-ana") { _, at -> receipt().copy(at = at) }
        s.outbox.flush()
        assertEquals(posted, rig.server.messages.size, "switch off: the queued read receipt is not sent")
        assertTrue(bob.repo.dueOutbox(Long.MAX_VALUE).isEmpty(), "and it is dropped, not held")
        bob.readReceiptsSwitch = { true }
        s.outbox.enqueueControl("acc-ana") { _, at -> receipt().copy(at = at) }
        s.outbox.flush()
        assertEquals(posted + 1, rig.server.messages.size, "switch on: it is sent")
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
    fun unblockIsLocalFirstAndRetried() = runTest {
        val (_, s, _) = joined()
        s.block("acc-ana"); s.settle()
        assertTrue("acc-ana" in s.blockedIds.value)
        social.failUnblock = true
        s.unblock("acc-ana"); s.settle()
        assertFalse("acc-ana" in s.blockedIds.value)
        assertTrue(social.unblocked.isEmpty())
        social.failUnblock = false
        s.syncInbox()
        assertEquals(listOf("acc-ana"), social.unblocked)
        s.syncInbox()
        assertEquals(listOf("acc-ana"), social.unblocked, "sent once")
    }

    @Test
    fun unblockDropsABlockTheServerNeverGot() = runTest {
        val (_, s, _) = joined()
        social.failBlock = true
        s.block("acc-ana"); s.settle()
        social.failBlock = false
        s.unblock("acc-ana"); s.settle()
        s.syncInbox()
        assertTrue(social.blocked.isEmpty())
        assertEquals(listOf("acc-ana"), social.unblocked)
        assertFalse("acc-ana" in s.blockedIds.value)
    }

    @Test
    fun blockingAgainDropsAnUnblockTheServerNeverGot() = runTest {
        val (_, s, _) = joined()
        s.block("acc-ana"); s.settle()
        social.failUnblock = true
        s.unblock("acc-ana"); s.settle()
        s.block("acc-ana"); s.settle()
        social.failUnblock = false
        s.syncInbox()
        assertTrue(social.unblocked.isEmpty())
        assertTrue("acc-ana" in s.blockedIds.value)
    }

    @Test
    fun failedWelcomeStrangerIsNotShownAsARequest() = runTest {
        // A real message request: a stranger (ana, not a contact) starts a conversation and bob joins it.
        val rig = Rig().start()
        val bob = rig.bob1
        rig.ana1.manager.create("acc-bob")
        bob.processor.sync()
        // A Welcome that failed to open from another stranger leaves only a placeholder.
        bob.repo.upsertChat(ChatRecord("acc-mallory", isRequest = true, unread = 0, muted = false, lastActivity = NOW))
        bob.repo.insertMessage(MessageRecord("join-failed-7", "acc-mallory", null, "acc-mallory", "d", NOW, NOW, """{"at":1,"id":"join-failed-7","k":"joinFailed","v":1}""".encodeToByteArray(), "delivered", undecryptable = true))
        val s = store(bob)
        assertEquals(listOf("acc-ana"), s.requests.value.map { it.id }, "only the verified request is shown")
        assertTrue(s.requests.value.single().isRequest)
        assertTrue(s.chats.value.none { it.id == "acc-mallory" })
        // The same account sending a real message later (a real conversation) is a request like any other.
        bob.repo.insertMessage(MessageRecord("m-1", "acc-mallory", null, "acc-mallory", "d", NOW + 1, NOW, org.example.app.messaging.content.ContentCodec.encode(ContentEnvelope.Text("m-1", NOW, "hi")), "delivered"))
        s.refresh()
        assertEquals(setOf("acc-ana", "acc-mallory"), s.requests.value.map { it.id }.toSet())
    }

    @Test
    fun inactiveConversationIsNotRemoval() = runTest {
        val (rig, s, conv) = joined()
        rig.ana1.send(conv, ContentEnvelope.Text("m-1", NOW, "hello"))
        s.syncInbox()
        val before = assertNotNull(s.chat("acc-ana"))
        assertTrue(before.messages.any { it.id == "m-1" })
        rig.bob1.repo.upsertConversation(rig.bob1.repo.conversation(conv.serverId)!!.copy(active = false))
        s.refresh()
        // Nothing about the chat changes: it stays in the list, with the same lines and no "removed" line.
        assertEquals(before, assertNotNull(s.chat("acc-ana")))
        assertTrue(s.chats.value.any { it.id == "acc-ana" })
        assertTrue(s.chat("acc-ana")!!.messages.none { it.content is MessageContent.System })
    }

    @Test
    fun resumePendingCreatesIsExposed() = runTest {
        val rig = Rig().start()
        // Killed after the server applied the create and before anything was written.
        rig.server.afterCommitApplied = { rig.server.afterCommitApplied = null; throw org.example.app.messaging.sync.SimulatedKill() }
        assertFailsWith<org.example.app.messaging.sync.SimulatedKill> { rig.ana1.manager.create("acc-bob") }
        assertNull(rig.ana1.repo.preferredConversation("acc-bob"))
        rig.ana1.restart()
        val s = store(rig.ana1)
        s.resumePendingCreates()
        assertNotNull(rig.ana1.repo.preferredConversation("acc-bob"), "the cut-short create was finished")
        assertEquals(1, rig.server.conversationIds().size)
        assertNotNull(s.chat("acc-bob"), "and the chat list was reloaded")
    }

    @Test
    fun peerDeleteAndReactOnReservedIdsChangeNothing() = runTest {
        val (rig, s, conv) = joined()
        val bob = rig.bob1
        val env = org.example.app.messaging.content.ContentCodec.encode(ContentEnvelope.Text("x", NOW, "x"))
        for (id in listOf("call-mine", "join-failed-3")) {
            bob.repo.insertMessage(MessageRecord(id, "acc-ana", conv.serverId, "acc-ana", null, NOW, NOW, env, "delivered"))
            rig.ana1.send(conv, ContentEnvelope.Delete("d-$id", NOW, id))
            rig.ana1.send(conv, ContentEnvelope.React("r-$id", NOW, id, "+"))
        }
        s.syncInbox()
        for (id in listOf("call-mine", "join-failed-3")) {
            assertFalse(bob.repo.message(id)!!.deleted, "$id not deleted by a peer")
            assertTrue(bob.repo.reactions(id).isEmpty(), "$id has no peer reaction")
        }
    }

    @Test
    fun peerMessageIdsWithReservedPrefixesAreRejected() = runTest {
        val (rig, s, conv) = joined()
        rig.ana1.send(conv, ContentEnvelope.Text("call-x", NOW, "fake call line"))
        rig.ana1.send(conv, ContentEnvelope.Text("join-failed-9", NOW, "fake placeholder"))
        rig.ana1.send(conv, ContentEnvelope.Text("ok-1", NOW, "fine"))
        s.syncInbox()
        assertNull(rig.bob1.repo.message("call-x"))
        assertNull(rig.bob1.repo.message("join-failed-9"))
        assertNotNull(rig.bob1.repo.message("ok-1"))
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

    /** A chat whose members cannot be read shows no number and no verified state, cannot be verified, and says so. */
    @Test
    fun unreadableMembersHideTheNumberAndTheVerifiedState() = runTest {
        val (rig, _, conv) = joined()
        val d = rig.bob1
        val s = LiveChatStore(
            d.me, d.mls, d.api, d.repo, d.lock, d.clock, d.manager, d.processor,
            org.example.app.messaging.sync.Membership(d.me, d.mls, realMemberKeys = true, clock = d.clock),
            d.sealer(), null, { true }, 10, MediaDirs(tmp("blob"), tmp("open"), tmp("temp")), social, scope, mediaSweep = {},
        )
        assertNotNull(s.safetyNumber("acc-ana"))
        s.setVerified(true, "acc-ana")
        assertEquals(true, s.safetyNumber("acc-ana")?.isVerified)
        assertFalse(s.membersUnreadable("acc-ana"))

        org.example.app.messaging.sync.Membership.markUnreadable(conv.serverId, d.repo)
        assertTrue(s.membersUnreadable("acc-ana"))
        assertNull(s.safetyNumber("acc-ana"))
        val verified = d.repo.safety("acc-ana")?.verified
        s.setVerified(false, "acc-ana")
        assertEquals(verified, d.repo.safety("acc-ana")?.verified, "unchanged, cannot be re-verified")
    }

    // Chat list actions and saved drafts

    private fun extraChats(repo: org.example.app.messaging.ports.Repository, n: Int) =
        (1..n).map { "other-$it" }.onEach { repo.upsertChat(ChatRecord(it, isRequest = false, unread = 0, muted = false, lastActivity = NOW + 1)) }

    @Test
    fun pinMuteAndArchiveAreSavedAndSurviveARestart() = runTest {
        val (rig, s, _) = joined()
        s.setPinned("acc-ana", true)
        s.setMuted("acc-ana", true)
        s.settle()
        assertNotNull(rig.bob1.repo.chat("acc-ana")!!.pinnedAt)
        s.setArchived("acc-ana", true)
        s.settle()
        val row = rig.bob1.repo.chat("acc-ana")!!
        assertNull(row.pinnedAt, "archiving unpins")
        assertTrue(row.muted)
        assertTrue(row.archived)
        assertEquals(setOf("acc-ana"), s.mutedChatIds.value)
        val again = store(rig.bob1).chat("acc-ana")!!
        assertNull(again.pinnedAt)
        assertTrue(again.muted)
        assertTrue(again.archived)
        s.setPinned("acc-ana", false); s.setMuted("acc-ana", false); s.setArchived("acc-ana", false); s.settle()
        assertNull(rig.bob1.repo.chat("acc-ana")!!.pinnedAt)
        assertTrue(s.mutedChatIds.value.isEmpty())
    }

    @Test
    fun theSixthPinIsRefused() = runTest {
        val (rig, s, _) = joined()
        val others = extraChats(rig.bob1.repo, 5)
        s.refresh()
        // No settle between the calls: the limit holds for pins that are still queued.
        assertTrue(s.setPinned("acc-ana", true))
        others.take(4).forEach { assertTrue(s.setPinned(it, true)) }
        assertFalse(s.setPinned(others[4], true))
        s.settle()
        assertEquals(5, rig.bob1.repo.chats().count { it.pinnedAt != null })
        assertNull(rig.bob1.repo.chat(others[4])!!.pinnedAt)
        assertTrue(s.setPinned("acc-ana", true), "pinning a pinned chat again is not a sixth pin")
        assertTrue(s.setPinned("acc-ana", false))
        assertTrue(s.setPinned(others[4], true))
        s.settle()
        assertNotNull(rig.bob1.repo.chat(others[4])!!.pinnedAt)
    }

    @Test
    fun pinnedChatSortsFirst() = runTest {
        val (rig, s, _) = joined()
        val others = extraChats(rig.bob1.repo, 2) // newer than the chat with Ana
        s.refresh()
        assertEquals("acc-ana", s.chats.value.last().id)
        s.setPinned("acc-ana", true); s.settle()
        assertEquals("acc-ana", s.chats.value.first().id)
        assertTrue(others.all { id -> s.chats.value.any { it.id == id } })
    }

    @Test
    fun anIncomingMessageNeverChangesArchivedOrMutedButIsStoredAndCounted() = runTest {
        val (rig, s, conv) = joined()
        s.setArchived("acc-ana", true); s.setMuted("acc-ana", true); s.settle()
        rig.ana1.send(conv, ContentEnvelope.Text("m-new", NOW + 10, "are you there"))
        rig.bob1.processor.sync()
        s.refresh()
        val row = rig.bob1.repo.chat("acc-ana")!!
        assertTrue(row.archived)
        assertTrue(row.muted)
        assertEquals(1, row.unread)
        assertNotNull(rig.bob1.repo.message("m-new"))
        val chat = s.chat("acc-ana")!!
        assertTrue(chat.archived && chat.muted)
        assertEquals(1, chat.unreadCount)
    }

    @Test
    fun draftsAreSavedInTheDatabaseAndBlankRemovesThem() = runTest {
        val (rig, s, _) = joined()
        s.setDraft("acc-ana", "half a thought"); s.settle()
        assertEquals("half a thought", rig.bob1.repo.draft("acc-ana"))
        assertEquals("half a thought", s.chat("acc-ana")!!.draft)
        assertEquals("half a thought", store(rig.bob1).chat("acc-ana")!!.draft, "survives a restart")
        s.setDraft("acc-ana", "   "); s.settle()
        assertNull(rig.bob1.repo.draft("acc-ana"))
        assertNull(s.chat("acc-ana")!!.draft)
    }

    @Test
    fun aDraftShowsAtOnceAndTheLastOneWinsWhenTypingFast() = runTest {
        val (rig, s, _) = joined()
        "hello".indices.forEach { s.setDraft("acc-ana", "hello".substring(0, it + 1)) }
        assertEquals("hello", s.chat("acc-ana")!!.draft)
        s.settle()
        assertEquals("hello", rig.bob1.repo.draft("acc-ana"))
    }

    @Test
    fun draftsAreWipedWithTheAccountAndNeverSeenByAnotherAccount() = runTest {
        val (rig, s, _) = joined()
        s.setDraft("acc-ana", "private"); s.settle()
        assertEquals("private", rig.bob1.repo.draft("acc-ana"))
        assertNull(rig.ana1.repo.draft("acc-bob"), "another account's database has no such draft")
        s.shutdown()
        rig.bob1.repo.wipe()
        assertTrue(rig.bob1.repo.drafts().isEmpty())
        assertNull(store(rig.bob1).chat("acc-ana")?.draft)
    }

    @Test
    fun archivingUnpinsAndFreesAPlaceAndUnarchivingDoesNotRepin() = runTest {
        val (rig, s, _) = joined()
        val others = extraChats(rig.bob1.repo, 5)
        s.refresh()
        assertTrue(s.setPinned("acc-ana", true))
        others.take(4).forEach { assertTrue(s.setPinned(it, true)) }
        s.settle()
        assertFalse(s.setPinned(others[4], true))
        s.setArchived("acc-ana", true) // not settled: the queued archive already counts
        assertTrue(s.setPinned(others[4], true))
        s.settle()
        assertNull(rig.bob1.repo.chat("acc-ana")!!.pinnedAt)
        assertTrue(rig.bob1.repo.chat("acc-ana")!!.archived)
        s.setArchived("acc-ana", false); s.settle()
        assertNull(rig.bob1.repo.chat("acc-ana")!!.pinnedAt)
    }

    private fun storeOn(d: TestDevice, repo: org.example.app.messaging.ports.Repository) = LiveChatStore(
        device = d.me, mls = d.mls, server = d.api, repo = repo, lock = d.lock, clock = d.clock, conversations = d.manager,
        inbox = d.processor, membership = d.membership, sealer = d.sealer(), escrow = d.escrow,
        readReceiptsEnabled = { d.readReceiptsSwitch() }, maxAttachmentBytes = 1234L,
        dirs = MediaDirs(tmp("blob"), tmp("open"), tmp("temp")), social = social, scope = scope,
    )

    @Test
    fun aDraftTypedWhileAReloadIsRunningIsNotOverwrittenByIt() = runTest {
        val (rig, _, _) = joined()
        val real = rig.bob1.repo
        val entered = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val gated = object : org.example.app.messaging.ports.Repository by real {
            override fun preferredConversation(chatId: String): org.example.app.messaging.ports.ConversationRecord? {
                if (armed.get()) { entered.countDown(); assertTrue(gate.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
                return real.preferredConversation(chatId)
            }
        }
        val s = storeOn(rig.bob1, gated)
        armed.set(true)
        val reloading = kotlin.concurrent.thread { s.refresh() }
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS), "the reload is under way")
        val typing = kotlin.concurrent.thread { s.setDraft("acc-ana", "new") }
        typing.join(300) // blocked behind the reload, or already done; either way it must win
        armed.set(false)
        gate.countDown()
        reloading.join(10_000)
        typing.join(10_000)
        s.settle()
        assertEquals("new", s.chat("acc-ana")!!.draft)
        assertEquals("new", real.draft("acc-ana"))
    }

    @Test
    fun aDraftForAChatWithNoRowYetIsKeptAndStoredWhenAnIncomingMessageCreatesTheRow() = runTest {
        val (rig, s, _) = joined()
        s.setDraft("acc-new", "hello"); s.settle()
        assertNull(rig.bob1.repo.draft("acc-new"))
        rig.bob1.repo.upsertChat(ChatRecord("acc-new", isRequest = false, unread = 0, muted = false, lastActivity = NOW))
        s.refresh(); s.settle()
        assertEquals("hello", rig.bob1.repo.draft("acc-new"))
        assertEquals("hello", s.chat("acc-new")!!.draft)
    }

    @Test
    fun aDraftForANewChatIsStoredWhenTheChatIsCreatedByOpeningIt() = runTest {
        val (rig, s, _) = joined()
        s.setDraft("acc-fresh", "first words"); s.settle()
        s.ensureChat("acc-fresh")
        s.settle()
        assertEquals("first words", rig.bob1.repo.draft("acc-fresh"))
        assertEquals("first words", store(rig.bob1).chat("acc-fresh")!!.draft)
    }

    @Test
    fun sendReturningIdQueuesOnceAndReturnsTheStoredMessageId() = runTest {
        val (rig, s, _) = joined()
        // Real time: the wait inside is a real timeout, not a virtual one.
        val id = kotlinx.coroutines.withContext(Dispatchers.Default) { s.sendReturningId("from a notification", "acc-ana") }
        assertNotNull(id)
        s.settle()
        val rows = rig.bob1.repo.messages("acc-ana", null, 20).filter { it.senderAccount == "acc-bob" }
        assertEquals(listOf(id), rows.map { it.id })
        assertEquals("sent", rows.single().status)
    }

    @Test
    fun sendReturningIdQueuesNothingForBlankOrClosedStore() = runTest {
        val (rig, s, _) = joined()
        assertNull(s.sendReturningId("   ", "acc-ana"))
        s.shutdown()
        assertNull(s.sendReturningId("late", "acc-ana"))
        assertTrue(rig.bob1.repo.messages("acc-ana", null, 20).none { it.senderAccount == "acc-bob" })
    }

    @Test
    fun aWriterSlowerThanTheWaitReportsNullAndNeverEnqueuesLater() = runTest {
        val (rig, s, _) = joined()
        val gate = java.util.concurrent.CountDownLatch(1)
        s.beforeClaimForTest = { gate.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        val result = kotlinx.coroutines.withContext(Dispatchers.Default) { s.sendReturningId("too late", "acc-ana", waitMs = 100) }
        assertNull(result)
        gate.countDown() // the writer now gets to the job
        s.settle()
        assertTrue(rig.bob1.repo.messages("acc-ana", null, 20).none { it.senderAccount == "acc-bob" }, "nothing was queued after null")
    }

    @Test
    fun aWriterThatClaimsJustBeforeTheWaitEndsStillReturnsTheId() = runTest {
        val (rig, s, _) = joined()
        val gate = java.util.concurrent.CountDownLatch(1)
        s.afterClaimForTest = { gate.await(5, java.util.concurrent.TimeUnit.SECONDS) } // claimed, enqueue under way
        val pending = CoroutineScope(Dispatchers.Default).async { s.sendReturningId("just in time", "acc-ana", waitMs = 100) }
        Thread.sleep(400) // past the wait
        gate.countDown()
        val id = pending.await()
        assertNotNull(id)
        s.settle()
        assertEquals(listOf(id), rig.bob1.repo.messages("acc-ana", null, 20).filter { it.senderAccount == "acc-bob" }.map { it.id })
    }

    @Test
    fun aPinnedMutedChatKeepsItsPinAndMuteWhenAMessageArrives() = runTest {
        val (rig, s, conv) = joined()
        s.setPinned("acc-ana", true); s.setMuted("acc-ana", true); s.settle()
        rig.ana1.send(conv, ContentEnvelope.Text("m-pin", NOW + 10, "hello"))
        rig.bob1.processor.sync()
        s.refresh()
        val row = rig.bob1.repo.chat("acc-ana")!!
        assertNotNull(row.pinnedAt)
        assertTrue(row.muted)
        assertEquals(1, row.unread)
    }

    @Test
    fun anArchivedChatCannotBePinnedAndNothingIsWritten() = runTest {
        val (rig, s, _) = joined()
        s.setArchived("acc-ana", true); s.settle()
        assertFalse(s.setPinned("acc-ana", true))
        s.settle()
        assertNull(rig.bob1.repo.chat("acc-ana")!!.pinnedAt)
    }

    @Test
    fun aReadReceiptFromAnotherDeviceLowersUnreadWithoutUndoingAPin() = runTest {
        val (rig, s, _) = joined()
        val bob = rig.bob1
        bob.repo.insertMessage(MessageRecord("in-r", "acc-ana", null, "acc-ana", "ana1", NOW, NOW, org.example.app.messaging.content.ContentCodec.encode(ContentEnvelope.Text("in-r", NOW, "x")), "delivered"))
        bob.repo.setUnread("acc-ana", 3)
        bob.repo.reduceUnread("acc-ana", 1)
        bob.repo.setPinnedAt("acc-ana", 5)
        bob.repo.reduceUnread("acc-ana", 10)
        assertEquals(0, bob.repo.chat("acc-ana")!!.unread)
        assertEquals(5L, bob.repo.chat("acc-ana")!!.pinnedAt)
    }

    @Test
    fun aReplyDraftAppendedToAnExistingOneSurvivesANewStoreOnTheSameDatabase() = runTest {
        val (rig, s, _) = joined()
        s.setDraft("acc-ana", "half a thought"); s.settle()
        val typed = s.chat("acc-ana")!!.draft.orEmpty()
        s.setDraft("acc-ana", typed + "\n" + "my reply"); s.settle()
        assertEquals("half a thought\nmy reply", store(rig.bob1).chat("acc-ana")!!.draft)
    }
}
