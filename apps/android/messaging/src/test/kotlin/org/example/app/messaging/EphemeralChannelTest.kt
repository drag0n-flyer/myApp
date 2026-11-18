package org.example.app.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.example.app.contracts.GroupId
import org.example.app.contracts.ServerEvent
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.TestDevice

class EphemeralChannelTest {
    private class Sent(val conversationId: String, val epoch: Long, val ciphertext: ByteArray)

    private class Pair2(val rig: Rig, val conv: ConversationRecord) {
        val sent = mutableListOf<Sent>()
        var failSend = false
        lateinit var ana: EphemeralChannel
        lateinit var bob: EphemeralChannel
    }

    private fun channel(d: TestDevice, sent: MutableList<Sent>, fail: () -> Boolean) = EphemeralChannel(
        d.me, d.mls, d.repo, d.lock, d.clock,
    ) { id, epoch, ct ->
        // The transport is a network call: the MLS lock must not be held.
        assertFalse(d.lock.isHeld, "send under the MLS lock")
        if (fail()) error("offline")
        sent += Sent(id, epoch, ct)
    }

    private suspend fun setup(): Pair2 {
        val rig = Rig().start()
        rig.bob1.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        rig.ana1.repo.upsertContact(ContactRecord("acc-bob", "bob", "Bob", true, NOW))
        val conv = rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        return Pair2(rig, conv).also { p ->
            p.ana = channel(rig.ana1, p.sent) { p.failSend }
            p.bob = channel(rig.bob1, mutableListOf()) { false }
        }
    }

    private fun Sent.asFrame(from: String = "acc-ana", device: String = "ana1") =
        ServerEvent.ConvEphemeral(conversationId, epoch, from, device, ciphertext)

    private suspend fun Pair2.receive(frame: ServerEvent.ConvEphemeral): EphemeralEvent? {
        val got = mutableListOf<EphemeralEvent>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { bob.events.toList(got) }
        bob.onFrame(frame)
        job.cancel()
        return got.singleOrNull()
    }

    @Test fun typingRoundTrips() = runTest {
        val p = setup()
        assertTrue(p.ana.send("acc-bob", ContentEnvelope.Typing("t1", NOW, true)))
        val s = p.sent.single()
        assertEquals(p.conv.serverId, s.conversationId)
        assertEquals(p.rig.ana1.repo.conversation(p.conv.serverId)!!.epoch, s.epoch)
        val event = p.receive(s.asFrame())!!
        assertEquals("acc-ana", event.chatId); assertEquals("acc-ana", event.accountId)
        assertEquals(true, assertIs<ContentEnvelope.Typing>(event.body).on)
        p.rig.assertLockDiscipline()
    }

    @Test fun presenceRoundTripsBothWays() = runTest {
        val p = setup()
        assertTrue(p.ana.send("acc-bob", ContentEnvelope.Presence("p1", NOW, "online")))
        assertEquals("online", assertIs<ContentEnvelope.Presence>(p.receive(p.sent.single().asFrame())!!.body).state)
    }

    @Test fun onlyTypingAndPresenceMaySend() = runTest {
        val p = setup()
        assertFailsWith<IllegalArgumentException> { p.ana.send("acc-bob", ContentEnvelope.Text("x", NOW, "hello")) }
        assertTrue(p.sent.isEmpty())
    }

    @Test fun anUnknownChatOrANetworkFailureIsDroppedQuietly() = runTest {
        val p = setup()
        assertFalse(p.ana.send("acc-nobody", ContentEnvelope.Typing("t", NOW, true)))
        p.failSend = true
        assertFalse(p.ana.send("acc-bob", ContentEnvelope.Typing("t", NOW, true)))
    }

    @Test fun wrongEpochUnknownConversationAndGarbageAreDropped() = runTest {
        val p = setup()
        p.ana.send("acc-bob", ContentEnvelope.Typing("t1", NOW, true))
        val s = p.sent.single()
        assertNull(p.receive(ServerEvent.ConvEphemeral(s.conversationId, s.epoch + 1, "acc-ana", "ana1", s.ciphertext)), "future epoch")
        assertNull(p.receive(ServerEvent.ConvEphemeral(s.conversationId, s.epoch - 1, "acc-ana", "ana1", s.ciphertext)), "old epoch")
        assertNull(p.receive(ServerEvent.ConvEphemeral("no-such", s.epoch, "acc-ana", "ana1", s.ciphertext)), "unknown conversation")
        assertNull(p.receive(ServerEvent.ConvEphemeral(s.conversationId, s.epoch, "acc-ana", "ana1", ByteArray(40) { it.toByte() })), "garbage")
        assertNull(p.receive(ServerEvent.ConvEphemeral(s.conversationId, s.epoch, "acc-ana", "ana1", s.ciphertext.copyOf(s.ciphertext.size - 3))), "truncated")
        assertNull(p.receive(ServerEvent.ConvEphemeral(s.conversationId, s.epoch, "acc-ana", "ana1", ByteArray(0))), "empty")
        // The good one still works afterwards, and a replay of it does not.
        assertIs<ContentEnvelope.Typing>(p.receive(s.asFrame())!!.body)
        assertNull(p.receive(s.asFrame()), "replay")
        p.rig.assertLockDiscipline()
    }

    @Test fun aSignalFromMyOwnAccountOrABlockedPeerIsDropped() = runTest {
        val p = setup()
        p.ana.send("acc-bob", ContentEnvelope.Typing("t1", NOW, true))
        p.rig.bob1.repo.block("acc-ana", NOW)
        assertNull(p.receive(p.sent.single().asFrame()))
    }

    @Test fun aPayloadThatIsNotATypingOrPresenceSignalIsDropped() = runTest {
        val p = setup()
        val group = GroupId(p.conv.groupId)
        val text = p.rig.ana1.lock.withLock {
            p.rig.ana1.mls.encrypt(ContentCodec.encode(ContentEnvelope.Text("x", NOW, "I am a message")), group)
        }
        assertNull(p.receive(ServerEvent.ConvEphemeral(p.conv.serverId, text.epoch, "acc-ana", "ana1", text.ciphertext)))
        val msg = p.rig.bob1.repo.messages("acc-ana", null, 10)
        assertTrue(msg.isEmpty(), "nothing stored")
    }

    @Test fun nothingIsStoredAndStoredMessagesStillDecryptInOrder() = runTest {
        val p = setup()
        val bob = p.rig.bob1
        p.rig.ana1.send(p.conv, ContentEnvelope.Text("m1", NOW, "first"))
        p.ana.send("acc-bob", ContentEnvelope.Typing("t1", NOW, true))
        p.rig.ana1.send(p.conv, ContentEnvelope.Text("m2", NOW + 1, "second"))
        p.ana.send("acc-bob", ContentEnvelope.Typing("t2", NOW, false))
        val processedBefore = bob.repo.processedCount()
        // The signals reach bob first, out of band, before the stored messages are fetched.
        p.sent.forEach { assertIs<ContentEnvelope.Typing>(p.receive(it.asFrame())!!.body) }
        assertEquals(processedBefore, bob.repo.processedCount(), "an ephemeral signal marks nothing processed")
        assertTrue(bob.repo.messages("acc-ana", null, 10).isEmpty())
        bob.processor.sync()
        assertEquals(listOf("m1", "m2"), bob.repo.messages("acc-ana", null, 10).map { it.id })
        assertEquals(2, bob.repo.chat("acc-ana")!!.unread)
        p.rig.assertLockDiscipline()
    }
}
