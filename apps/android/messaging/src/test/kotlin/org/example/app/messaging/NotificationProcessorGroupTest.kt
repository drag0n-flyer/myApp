package org.example.app.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.NotificationOutcome
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.sync.GroupWorld
import org.example.app.messaging.sync.NOW

/** Push for a group message: the group's name, "Name: text", never a request, silent when muted or blocked. */
class NotificationProcessorGroupTest {
    private val chatId = "g:conv-0001"

    private class Setup(val w: GroupWorld, val np: NotificationProcessor)

    private suspend fun setup(): Setup {
        val w = GroupWorld.trio()
        val ana = w.device("ana1")
        ana.groups.create(listOf("acc-bob", "acc-cat"), "Book club", null)
        ana.outbox.flush()
        for (id in listOf("bob1", "cat1")) w.device(id).processor.sync()
        return Setup(w, w.device("bob1").notifications())
    }

    /** A group push is never decrypted by the push: it asks for the sync, and the sync's outcomes are what shows. */
    private suspend fun Setup.push(from: String, env: ContentEnvelope): NotificationOutcome {
        val sender = w.device(from)
        sender.send(sender.repo.preferredConversation(chatId)!!, env)
        val item = w.server.pending("acc-bob/bob1").last()
        assertIs<NotificationOutcome.FetchInbox>(w.device("bob1").lock.withLock { np.onMessagePush(pad(item.payload)) })
        return np.fetchOutcomes().lastOrNull() ?: NotificationOutcome.Nothing
    }

    @Test
    fun theTitleIsTheGroupAndTheBodyNamesTheSender() = runTest {
        val s = setup()
        s.w.device("bob1").repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        val outcome = s.push("ana1", ContentEnvelope.Text("m-1", NOW, "hello all"))
        assertIs<NotificationOutcome.Message>(outcome)
        assertEquals(chatId, outcome.chatId)
        assertEquals("Book club", outcome.senderName)
        assertEquals("Ana: hello all", outcome.preview)
    }

    @Test
    fun aMemberWhoIsNoContactIsStillNotARequestAndNamedByTheirProfile() = runTest {
        val s = setup()
        val bob = s.w.device("bob1")
        // Cat is no contact of bob's; her profile name is all bob knows.
        val cat = s.w.device("cat1")
        cat.outbox.enqueueControl(chatId) { id, at -> ContentEnvelope.Profile(id, at, "Cat Smith", null, 2) }
        cat.outbox.flush()
        bob.processor.sync()
        val outcome = s.push("cat1", ContentEnvelope.Photo("p-1", NOW, org.example.app.messaging.content.Att("a", ByteArray(32), ByteArray(32), 10, 20), 1, 1))
        assertIs<NotificationOutcome.Message>(outcome)
        assertEquals("Cat Smith: Photo", outcome.preview)
        assertEquals("Book club", outcome.senderName)
    }

    @Test
    fun aMemberWithNoKnownNameIsSomeone() = runTest {
        val s = setup()
        val outcome = s.push("ana1", ContentEnvelope.Text("m-1", NOW, "hi"))
        assertIs<NotificationOutcome.Message>(outcome)
        assertEquals("Someone: hi", outcome.preview)
    }

    @Test
    fun aMutedGroupIsSilentAndSoIsABlockedSender() = runTest {
        val s = setup()
        val bob = s.w.device("bob1")
        bob.repo.setMuted(chatId, true)
        assertIs<NotificationOutcome.Nothing>(s.push("ana1", ContentEnvelope.Text("m-1", NOW, "muted")))
        bob.repo.setMuted(chatId, false)
        bob.repo.block("acc-cat", NOW)
        assertIs<NotificationOutcome.Nothing>(s.push("cat1", ContentEnvelope.Text("m-2", NOW, "from blocked")))
        assertEquals(1, bob.repo.chat(chatId)!!.unread, "only ana's muted message counts; the blocked one does not")
        assertIs<NotificationOutcome.Message>(s.push("ana1", ContentEnvelope.Text("m-3", NOW, "from ana")))
    }

    @Test
    fun aRawGroupPushIsNeverDecryptedByThePushAndGoesToTheSync() = runTest {
        val s = setup()
        val bob = s.w.device("bob1")
        val cat = s.w.device("cat1")
        cat.send(cat.repo.preferredConversation(chatId)!!, ContentEnvelope.Text("m-1", NOW, "hello"))
        val item = s.w.server.pending("acc-bob/bob1").last()
        val outcome = bob.lock.withLock { s.np.onMessagePush(pad(item.payload)) }
        assertIs<NotificationOutcome.FetchInbox>(outcome)
        assertEquals(null, bob.repo.message("m-1"), "not decrypted by the push")
        assertEquals(false, bob.repo.isProcessed(org.example.app.messaging.ports.PayloadHash.of(item.payload)))
    }

    @Test
    fun aMalformedOrShortPayloadNeverCrashesTheSniff() {
        val bad = listOf(
            byteArrayOf(), byteArrayOf(0), byteArrayOf(0, 1, 0, 2), byteArrayOf(0, 1, 0, 2, 0x40), byteArrayOf(0, 1, 0, 2, 0x7f.toByte(), 1, 2),
            byteArrayOf(0, 1, 0, 2, 0xC0.toByte(), 1, 2, 3), byteArrayOf(0, 1, 0, 2, 0x80.toByte(), 0x7f, -1, -1, 1),
            byteArrayOf(0, 1, 0, 2, 0x20, 1), ByteArray(3) { -1 },
        )
        for (p in bad) assertEquals(null, NotificationProcessor.sniffGroupId(p), p.toList().toString())
        assertEquals(listOf<Byte>(7, 8), NotificationProcessor.sniffGroupId(byteArrayOf(0, 1, 0, 2, 2, 7, 8, 9))!!.toList())
        assertEquals(null, NotificationProcessor.sniffGroupId(byteArrayOf(0, 1, 0, 3, 2, 7, 8)), "a Welcome is not a private message")
        assertEquals(null, NotificationProcessor.sniffGroupId(byteArrayOf(0, 1, 0, 2, 0)), "an empty group id is nothing")
    }

    @Test
    fun controlMessagesShowNothing() = runTest {
        val s = setup()
        assertIs<NotificationOutcome.Nothing>(s.push("ana1", ContentEnvelope.Timer("t-1", NOW, 86_400)))
        assertIs<NotificationOutcome.Nothing>(s.push("ana1", ContentEnvelope.Group("g-1", NOW, "Renamed", null, 2)))
    }
}
