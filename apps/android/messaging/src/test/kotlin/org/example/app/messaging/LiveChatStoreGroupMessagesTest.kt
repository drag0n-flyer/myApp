package org.example.app.messaging

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.MessageReceipt
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.ports.GroupMemberRecord
import org.example.app.messaging.sync.GroupWorld
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.TestDevice

/** The group parts of the live store: the tick over every member, receipts for Message info, names, the timer rule, my profile. */
class LiveChatStoreGroupMessagesTest {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val chatId = "g:conv-0001"

    private fun tmp(n: String): File = Files.createTempDirectory(n).toFile().also { it.deleteOnExit() }

    private fun store(d: TestDevice) = LiveChatStore(
        device = d.me, mls = d.mls, server = d.api, repo = d.repo, lock = d.lock, clock = d.clock, conversations = d.manager,
        inbox = d.processor, membership = d.membership, sealer = d.sealer(), escrow = d.escrow,
        readReceiptsEnabled = { d.readReceiptsSwitch() }, maxAttachmentBytes = 1234L,
        dirs = MediaDirs(tmp("blob"), tmp("open"), tmp("temp")),
        social = object : SocialPort {
            override suspend fun lookUpUser(username: String) = null
            override suspend fun block(accountId: String) = Unit
            override suspend fun unblock(accountId: String) = Unit
            override suspend fun report(accountId: String, conversationId: String?, messages: List<ReportedLine>) = Unit
        },
        scope = scope, fold = { it.lowercase() }, mediaSweep = { },
    )

    private suspend fun joined(): GroupWorld {
        val w = GroupWorld.trio()
        val ana = w.device("ana1")
        ana.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", false, NOW))
        ana.groups.create(listOf("acc-bob", "acc-cat"), "Book club", null)
        ana.outbox.flush()
        for (id in listOf("bob1", "cat1")) {
            w.device(id).processor.sync()
            w.device(id).receipts.attach(w.device(id).processor)
        }
        ana.processor.sync()
        return w
    }

    private suspend fun GroupWorld.send(id: String, from: String = "ana1") {
        val d = device(from)
        d.outbox.enqueue(ContentEnvelope.Text(id, NOW + 10, "hi"), chatId)
        d.outbox.flush()
    }

    private fun LiveChatStore.status(id: String) = messages(chatId, null, 50).first { it.id == id }.status

    @Test
    fun theTickWaitsForEveryOtherMemberAndAMemberWhoLeftDoesNotHoldItBack() = runTest {
        val w = joined()
        val ana = w.device("ana1")
        val s = store(ana)
        w.send("m-1")
        assertEquals(DeliveryStatus.Sent, s.status("m-1"))
        w.device("bob1").processor.sync()
        ana.processor.sync()
        assertEquals(DeliveryStatus.Sent, s.status("m-1"), "bob only")
        w.device("cat1").processor.sync()
        ana.processor.sync()
        assertEquals(DeliveryStatus.Delivered, s.status("m-1"))
        w.device("bob1").receipts.read(chatId, listOf("m-1"))
        w.device("bob1").outbox.flush()
        ana.processor.sync()
        assertEquals(DeliveryStatus.Delivered, s.status("m-1"), "cat has not read it")
        // Cat leaves: the stored list loses her, and bob's read now covers everyone left.
        ana.repo.setGroupMembers(chatId, listOf(GroupMemberRecord("acc-ana", "owner"), GroupMemberRecord("acc-bob", "member")), 9)
        assertEquals(DeliveryStatus.Read, s.status("m-1"))
        // With the Read receipts switch off a read tick shows as delivered, as in a one-to-one chat.
        ana.readReceiptsSwitch = { false }
        assertEquals(DeliveryStatus.Delivered, s.status("m-1"))
    }

    @Test
    fun aMessageThatHasNotLeftKeepsItsOwnStatus() = runTest {
        val w = joined()
        val ana = w.device("ana1")
        val s = store(ana)
        ana.outbox.enqueue(ContentEnvelope.Text("m-2", NOW + 11, "queued"), chatId)
        assertEquals(DeliveryStatus.Sending, s.status("m-2"))
    }

    @Test
    fun messageReceiptsListEachMemberWithTheirTimes() = runTest {
        val w = joined()
        val ana = w.device("ana1")
        val s = store(ana)
        w.send("m-1")
        w.device("bob1").processor.sync()
        w.device("cat1").processor.sync()
        w.device("bob1").receipts.read(chatId, listOf("m-1"))
        w.device("bob1").outbox.flush()
        ana.processor.sync()
        val got = s.messageReceipts("m-1")
        assertEquals(listOf("acc-bob", "acc-cat"), got.map { it.accountId })
        val bob = got.first { it.accountId == "acc-bob" }
        val cat = got.first { it.accountId == "acc-cat" }
        assertTrue(bob.deliveredAt != null && bob.readAt != null)
        assertTrue(cat.deliveredAt != null)
        assertNull(cat.readAt)
        assertEquals(emptyList<MessageReceipt>(), s.messageReceipts("nope"))
    }

    @Test
    fun aMembersNameComesFromTheirProfileEvenWhenTheyAreNoContact() = runTest {
        val w = joined()
        val ana = w.device("ana1")
        val s = store(ana)
        assertNull(s.personName("acc-cat"))
        val cat = w.device("cat1")
        cat.outbox.enqueueControl(chatId) { id, at -> ContentEnvelope.Profile(id, at, "Cat Smith", null, 2) }
        cat.outbox.flush()
        ana.processor.sync()
        s.refresh()
        assertEquals("Cat Smith", s.personName("acc-cat"))
        assertEquals("Cat Smith", s.profiles.value["acc-cat"]?.name)
        assertNull(s.contact("acc-cat"), "still no contact")
    }

    @Test
    fun aGroupTakesSendsFromAMemberAndRefusesSendsAndControlsOnceIAmRemovedOrLeft() = runTest {
        val w = joined()
        val bob = w.device("bob1")
        val s = store(bob)
        val sent = s.sendReturningId("hello", chatId)
        assertTrue(sent != null, "a member's send to a group is queued")
        bob.repo.setMyState(chatId, org.example.app.messaging.ports.MyState.REMOVED)
        val before = bob.repo.messages(chatId, null, 50).size
        assertNull(s.sendReturningId("again", chatId), "a removed member sends nothing")
        s.react("👍", sent!!, chatId)
        s.setTimer(chatId, 86_400)
        s.refresh()
        assertEquals(before, bob.repo.messages(chatId, null, 50).size, "no new line, no timer line")
        assertEquals(0, bob.repo.timerSeconds(chatId))
        bob.repo.setMyState(chatId, org.example.app.messaging.ports.MyState.LEFT)
        assertNull(s.sendReturningId("again", chatId), "nor one who left")
    }

    @Test
    fun aProfileNameOfSomeoneInMyRequestsShowsInTheGroupOnly() = runTest {
        val w = joined()
        val ana = w.device("ana1")
        ana.repo.upsertChat(org.example.app.messaging.ports.ChatRecord("acc-cat", isRequest = true, unread = 0, muted = false, lastActivity = 1))
        val s = store(ana)
        val cat = w.device("cat1")
        cat.outbox.enqueueControl(chatId) { id, at -> ContentEnvelope.Profile(id, at, "Cat Smith", null, 2) }
        cat.outbox.flush()
        ana.processor.sync()
        s.refresh()
        assertEquals("Cat Smith", s.personName("acc-cat"), "the group line uses the name")
        assertNull(s.profiles.value["acc-cat"], "no profile (photo) goes out for the request's one-to-one row")
    }

    @Test
    fun searchSkipsAGroupMessageFromABlockedSender() = runTest {
        val w = joined()
        val ana = w.device("ana1")
        val s = store(ana)
        w.send("m-1", from = "bob1")
        w.send("m-2", from = "cat1")
        ana.processor.sync()
        assertEquals(2, s.searchMessages("hi", 10).size)
        ana.repo.block("acc-cat", NOW)
        val hits = s.searchMessages("hi", 10)
        assertEquals(listOf("m-1"), hits.map { it.messageId })
    }

    @Test
    fun theTicksOfAScreenOfMessagesAreReadInOneReceiptQuery() = runTest {
        val w = joined()
        val ana = w.device("ana1")
        val counting = CountingRepo(ana.repo)
        val s = LiveChatStore(
            device = ana.me, mls = ana.mls, server = ana.api, repo = counting, lock = ana.lock, clock = ana.clock, conversations = ana.manager,
            inbox = ana.processor, membership = ana.membership, sealer = ana.sealer(), escrow = ana.escrow,
            readReceiptsEnabled = { ana.readReceiptsSwitch() }, maxAttachmentBytes = 1234L,
            dirs = MediaDirs(tmp("blob"), tmp("open"), tmp("temp")),
            social = object : SocialPort {
                override suspend fun lookUpUser(username: String) = null
                override suspend fun block(accountId: String) = Unit
                override suspend fun unblock(accountId: String) = Unit
                override suspend fun report(accountId: String, conversationId: String?, messages: List<ReportedLine>) = Unit
            },
            scope = scope, fold = { it.lowercase() }, mediaSweep = { },
        )
        for (i in 1..5) w.send("m-$i")
        counting.singleCalls = 0
        counting.batchCalls = 0
        s.messages(chatId, null, 50)
        assertEquals(0, counting.singleCalls, "never one query per message")
        assertEquals(1, counting.batchCalls)
    }

    private class CountingRepo(val inner: org.example.app.messaging.ports.Repository) :
        org.example.app.messaging.ports.Repository by inner {
        var singleCalls = 0
        var batchCalls = 0
        override fun receipts(messageId: String): List<org.example.app.messaging.ports.ReceiptRecord> { singleCalls++; return inner.receipts(messageId) }
        override fun receiptsFor(messageIds: List<String>): List<org.example.app.messaging.ports.ReceiptRecord> { batchCalls++; return inner.receiptsFor(messageIds) }
    }

    @Test
    fun aPlainMemberCannotSetTheTimerButAnOwnerCan() = runTest {
        val w = joined()
        val bob = w.device("bob1")
        val sb = store(bob)
        sb.setTimer(chatId, 86_400)
        sb.settle()
        assertEquals(0, bob.repo.timerSeconds(chatId))
        assertTrue(bob.repo.dueOutbox(Long.MAX_VALUE).none { ContentCodec.decode(it.envelope) is ContentEnvelope.Timer })
        val ana = w.device("ana1")
        val sa = store(ana)
        sa.setTimer(chatId, 86_400)
        sa.settle()
        assertEquals(86_400, ana.repo.timerSeconds(chatId))
    }

    @Test
    fun readingTheGroupSendsAReadReceiptToTheGroup() = runTest {
        val w = joined()
        val bob = w.device("bob1")
        val sb = store(bob)
        w.send("m-1")
        bob.processor.sync()
        sb.markRead(chatId)
        sb.settle()
        bob.outbox.flush()
        val ana = w.device("ana1")
        ana.processor.sync()
        assertTrue(ana.repo.receipts("m-1").any { it.accountId == "acc-bob" && it.kind == "read" })
    }

    @Test
    fun aNewGroupAnnouncesMyProfileAfterItsGroupEnvelope() = runTest {
        val w = GroupWorld.trio()
        val ana = w.device("ana1")
        ana.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", false, NOW))
        val s = store(ana)
        val made = s.createGroup(listOf("acc-bob", "acc-cat"), "Trio", null)
        s.settle()
        ana.outbox.flush()
        val bob = w.device("bob1")
        bob.processor.sync()
        assertEquals("Trio", bob.repo.chat(made.chatId)!!.title)
        assertEquals("Ana", bob.repo.profile("acc-ana")?.name, "the new group's members get my name")
        assertTrue(ana.repo.dueOutbox(Long.MAX_VALUE).isEmpty())
    }
}
