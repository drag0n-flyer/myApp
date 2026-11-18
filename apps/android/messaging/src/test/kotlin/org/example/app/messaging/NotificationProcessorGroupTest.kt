package org.example.app.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.NotificationOutcome
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.ports.GroupMemberRecord
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

    private suspend fun Setup.push(from: String, env: ContentEnvelope): NotificationOutcome {
        val sender = w.device(from)
        sender.send(sender.repo.preferredConversation(chatId)!!, env)
        val item = w.server.pending("acc-bob/bob1").last()
        return w.device("bob1").lock.withLock { np.onMessagePush(pad(item.payload)) }
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
    fun controlMessagesAndAnUnlistedSenderShowNothing() = runTest {
        val s = setup()
        assertIs<NotificationOutcome.Nothing>(s.push("ana1", ContentEnvelope.Timer("t-1", NOW, 86_400)))
        assertIs<NotificationOutcome.Nothing>(s.push("ana1", ContentEnvelope.Group("g-1", NOW, "Renamed", null, 2)))
        val bob = s.w.device("bob1")
        bob.repo.setGroupMembers(chatId, listOf(GroupMemberRecord("acc-ana", "owner"), GroupMemberRecord("acc-bob", "member")), 9)
        assertIs<NotificationOutcome.Nothing>(s.push("cat1", ContentEnvelope.Text("m-1", NOW, "ghost")), "no network here: the sender stays unlisted")
        assertEquals(null, bob.repo.message("m-1"))
    }
}
