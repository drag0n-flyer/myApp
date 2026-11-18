package org.example.app.messaging.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.ServerException
import org.example.app.messaging.content.Att
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.content.ReceiptKind
import org.example.app.messaging.ports.GroupMemberRecord

/** Send and receive in a group through the ordinary outbox and inbox: three members, the sender taken from MLS. */
class GroupMessagesTest {
    private val chatId = "g:conv-0001"

    /** ana (owner), bob and cat joined; everything queued at the start has been sent and read. */
    private suspend fun joined(): GroupWorld {
        val w = GroupWorld.trio()
        val ana = w.device("ana1")
        ana.groups.create(listOf("acc-bob", "acc-cat"), "Book club", null)
        ana.outbox.flush()
        for (id in listOf("bob1", "cat1")) w.device(id).processor.sync()
        ana.processor.sync()
        return w
    }

    private suspend fun GroupWorld.say(from: String, id: String, text: String, at: Long = NOW + 10) {
        val d = device(from)
        d.outbox.enqueue(ContentEnvelope.Text(id, at, text), chatId)
        d.outbox.flush()
    }

    @Test
    fun aMessageIsStoredInTheGroupChatWithTheSendersAccount() = runTest {
        val w = joined()
        w.say("ana1", "m-1", "hello all")
        for (id in listOf("bob1", "cat1")) {
            val d = w.device(id)
            d.processor.sync()
            val m = assertNotNull(d.repo.message("m-1"), id)
            assertEquals(chatId, m.chatId)
            assertEquals("acc-ana", m.senderAccount)
            assertEquals("conv-0001", m.conversationId)
            assertEquals("delivered", m.status)
            assertEquals(1, d.repo.chat(chatId)!!.unread)
            assertNull(d.repo.chat("acc-ana"), "no one-to-one chat appears")
        }
        assertEquals("sent", w.device("ana1").repo.message("m-1")!!.status)
    }

    @Test
    fun everyoneSeesWhoSaidWhat() = runTest {
        val w = joined()
        w.say("bob1", "b-1", "from bob", NOW + 11)
        w.say("cat1", "c-1", "from cat", NOW + 12)
        val ana = w.device("ana1")
        ana.processor.sync()
        assertEquals("acc-bob", ana.repo.message("b-1")!!.senderAccount)
        assertEquals("acc-cat", ana.repo.message("c-1")!!.senderAccount)
        val bob = w.device("bob1")
        bob.processor.sync()
        assertEquals("acc-cat", bob.repo.message("c-1")!!.senderAccount)
        assertEquals("sent", bob.repo.message("b-1")!!.status)
    }

    @Test
    fun everyKindWorksInAGroup() = runTest {
        val w = joined()
        val ana = w.device("ana1")
        val att = Att("att-1", ByteArray(32) { 1 }, ByteArray(32) { 2 }, 100, 128)
        ana.outbox.enqueue(ContentEnvelope.Photo("p-1", NOW + 20, att, 10, 10), chatId)
        ana.outbox.enqueue(ContentEnvelope.Voice("v-1", NOW + 21, Att("att-2", ByteArray(32) { 1 }, ByteArray(32) { 2 }, 100, 128), 3.0), chatId)
        ana.outbox.enqueue(ContentEnvelope.File("f-1", NOW + 22, Att("att-3", ByteArray(32) { 1 }, ByteArray(32) { 2 }, 100, 128), "a.pdf", "application/pdf"), chatId)
        w.say("ana1", "t-1", "text", NOW + 23)
        ana.outbox.enqueueControl(chatId) { id, at -> ContentEnvelope.React(id, at, "t-1", "👍") }
        ana.outbox.enqueueControl(chatId) { id, at -> ContentEnvelope.Edit(id, at, "t-1", "text edited") }
        ana.outbox.flush()
        val bob = w.device("bob1")
        bob.processor.sync()
        for (id in listOf("p-1", "v-1", "f-1", "t-1")) assertEquals(chatId, bob.repo.message(id)!!.chatId, id)
        assertEquals("acc-ana", bob.repo.reactions("t-1").single().accountId)
        assertNotNull(bob.repo.message("t-1")!!.editedAt)
        assertNotNull(bob.repo.attachmentForMessage("p-1"))
        ana.outbox.enqueueControl(chatId) { id, at -> ContentEnvelope.Delete(id, at, "t-1") }
        ana.outbox.flush()
        bob.processor.sync()
        assertTrue(bob.repo.message("t-1")!!.deleted)
    }

    @Test
    fun aSenderMissingFromTheStoredListIsLookedUpOnceAndThenAccepted() = runTest {
        val w = joined()
        val bob = w.device("bob1")
        // Bob's copy of the list is behind: it lacks cat, and the server has moved on to a newer version.
        bob.repo.setGroupMembers(chatId, listOf(GroupMemberRecord("acc-ana", "owner"), GroupMemberRecord("acc-bob", "member")), 5)
        w.server.touchGroupVersion("conv-0001", 9)
        w.say("cat1", "c-1", "late list")
        val before = w.server.calls.count { it == "acc-bob/bob1 conversation" }
        bob.processor.sync()
        assertEquals(1, w.server.calls.count { it == "acc-bob/bob1 conversation" } - before, "one fetch")
        assertNotNull(bob.repo.message("c-1"))
        // The list is the group sync's to write (with its system lines): the lookup only answers who may speak.
        assertEquals(listOf("acc-ana", "acc-bob"), bob.repo.groupMembers(chatId).map { it.accountId })
        // A sender the last lookup named costs no new one.
        w.say("cat1", "c-2", "known now")
        val mid = w.server.calls.count { it == "acc-bob/bob1 conversation" }
        bob.processor.sync()
        assertEquals(mid, w.server.calls.count { it == "acc-bob/bob1 conversation" })
        assertNotNull(bob.repo.message("c-2"))
    }

    @Test
    fun aSenderStillAbsentAfterTheLookupIsDroppedWithoutARow() = runTest {
        val w = joined()
        val bob = w.device("bob1")
        // The server's list no longer holds cat (removed), yet cat's device still encrypts for the old epoch.
        w.server.removeGroupAccount("conv-0001", "acc-cat")
        bob.repo.setGroupMembers(chatId, listOf(GroupMemberRecord("acc-ana", "owner"), GroupMemberRecord("acc-bob", "member")), 7)
        w.server.touchGroupVersion("conv-0001", 20)
        w.say("cat1", "c-1", "ghost")
        bob.processor.sync()
        assertNull(bob.repo.message("c-1"))
        assertTrue(bob.repo.messages(chatId, null, 50).none { it.undecryptable }, "no placeholder either")
        assertEquals(0, bob.repo.chat(chatId)!!.unread)
        assertTrue(bob.repo.rawItems(10).isEmpty(), "dropped, not retried for ever")
    }

    @Test
    fun aFailedLookupKeepsTheItemForTheNextSync() = runTest {
        val w = joined()
        val bob = w.device("bob1")
        bob.repo.setGroupMembers(chatId, listOf(GroupMemberRecord("acc-ana", "owner"), GroupMemberRecord("acc-bob", "member")), 5)
        w.server.touchGroupVersion("conv-0001", 9)
        w.say("cat1", "c-1", "after the outage")
        var fail = true
        w.server.onCall = { identity, call -> if (fail && identity == "acc-bob/bob1" && call == "conversation") throw ServerException(503, "unavailable", null, null) }
        bob.processor.sync()
        assertNull(bob.repo.message("c-1"))
        assertEquals(1, bob.repo.rawItems(10).size)
        fail = false
        bob.processor.sync()
        assertNotNull(bob.repo.message("c-1"))
        assertTrue(bob.repo.rawItems(10).isEmpty())
    }

    @Test
    fun aMessageFromABlockedMemberIsStoredButNeverCountedUnread() = runTest {
        val w = joined()
        val bob = w.device("bob1")
        bob.repo.block("acc-cat", NOW)
        w.say("cat1", "c-1", "from the blocked one")
        w.say("ana1", "a-1", "from ana", NOW + 11)
        bob.processor.sync()
        assertNotNull(bob.repo.message("c-1"))
        assertEquals(1, bob.repo.chat(chatId)!!.unread, "only ana's message counts")
    }

    @Test
    fun aTimerFromAPlainMemberIsIgnoredAndFromAnAdminAccepted() = runTest {
        val w = joined()
        val bob = w.device("bob1")
        val cat = w.device("cat1")
        cat.outbox.enqueueControl(chatId) { id, at -> ContentEnvelope.Timer(id, at, 86_400) }
        cat.outbox.flush()
        bob.processor.sync()
        assertEquals(0, bob.repo.timerSeconds(chatId), "cat is a plain member")
        assertTrue(bob.repo.messages(chatId, null, 50).none { it.id.startsWith("sys-timer") })
        val ana = w.device("ana1")
        ana.outbox.enqueueControl(chatId) { id, at -> ContentEnvelope.Timer(id, at + 5, 604_800) }
        ana.outbox.flush()
        bob.processor.sync()
        assertEquals(604_800, bob.repo.timerSeconds(chatId))
    }

    @Test
    fun aGroupEnvelopeFromTheAdminOnlyAfterTheOwnerMakesOneStillWorksAsBefore() = runTest {
        val w = joined()
        val bob = w.device("bob1")
        assertEquals("Book club", bob.repo.chat(chatId)!!.title)
    }

    @Test
    fun receiptsAreStoredPerMemberAndTheTickWaitsForEveryone() = runTest {
        val w = joined()
        for (id in listOf("bob1", "cat1")) w.device(id).receipts.attach(w.device(id).processor)
        w.say("ana1", "m-1", "tick me")
        val ana = w.device("ana1")
        val bob = w.device("bob1")
        val cat = w.device("cat1")
        bob.processor.sync()
        ana.processor.sync()
        assertEquals(listOf("acc-bob" to "delivered"), ana.repo.receipts("m-1").map { it.accountId to it.kind })
        assertEquals("sent", ana.repo.message("m-1")!!.status, "one of two is not delivered")
        cat.processor.sync()
        ana.processor.sync()
        assertEquals(listOf("acc-bob", "acc-cat"), ana.repo.receipts("m-1").map { it.accountId })
        // Read receipts from both members.
        bob.receipts.read(chatId, listOf("m-1"))
        cat.receipts.read(chatId, listOf("m-1"))
        bob.outbox.flush()
        cat.outbox.flush()
        ana.processor.sync()
        assertEquals(
            setOf("acc-bob" to "delivered", "acc-bob" to "read", "acc-cat" to "delivered", "acc-cat" to "read"),
            ana.repo.receipts("m-1").map { it.accountId to it.kind }.toSet(),
        )
        // A repeated receipt changes nothing.
        ana.repo.addReceipt("m-1", "acc-bob", "read", NOW + 99_000)
        assertEquals(4, ana.repo.receipts("m-1").size)
    }

    @Test
    fun aReceiptFromANonMemberOrForSomeoneElsesMessageIsIgnored() = runTest {
        val w = joined()
        val ana = w.device("ana1")
        val bob = w.device("bob1")
        w.say("ana1", "m-1", "mine")
        w.say("bob1", "b-1", "bobs", NOW + 11)
        ana.processor.sync()
        // Cat claims to have read bob's message: it is not ana's message, so ana stores nothing for it.
        val cat = w.device("cat1")
        cat.processor.sync()
        cat.outbox.enqueueControl(chatId) { id, at -> ContentEnvelope.Receipt(id, at, ReceiptKind.Read, listOf("b-1", "m-1", "nope")) }
        cat.outbox.flush()
        ana.processor.sync()
        assertTrue(ana.repo.receipts("b-1").isEmpty())
        assertEquals(listOf("acc-cat"), ana.repo.receipts("m-1").map { it.accountId })
        assertFalse(bob.repo.message("b-1") == null)
    }

    @Test
    fun aMemberProfileIsKeptFromAListedMember() = runTest {
        val w = joined()
        val bob = w.device("bob1")
        val cat = w.device("cat1")
        cat.outbox.enqueueControl(chatId) { id, at -> ContentEnvelope.Profile(id, at, "Cat Smith", null, 3) }
        cat.outbox.flush()
        bob.processor.sync()
        assertEquals("Cat Smith", bob.repo.profile("acc-cat")?.name)
    }
}
