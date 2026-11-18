package org.example.app.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.ServerEvent
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.TestDevice

/** Typing and presence carry the plain envelope JSON in the frame: no MLS call anywhere. */
class EphemeralChannelTest {
    private class Sent(val conversationId: String, val epoch: Long, val bytes: ByteArray)

    private class Pair2(val rig: Rig, val conv: ConversationRecord) {
        val sent = mutableListOf<Sent>()
        var failSend = false
        var baseline = listOf<kotlin.Pair<Int, Int>>()
        lateinit var ana: EphemeralChannel
        lateinit var bob: EphemeralChannel
    }

    private fun channel(d: TestDevice, sent: MutableList<Sent>, fail: () -> Boolean) = EphemeralChannel(d.me, d.repo) { id, epoch, bytes ->
        if (fail()) error("offline")
        sent += Sent(id, epoch, bytes)
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
            p.baseline = listOf(rig.ana1, rig.bob1).map { it.mls.processCalls to it.mls.processApplicationCalls }
        }
    }

    private fun Pair2.frame(bytes: ByteArray, from: String = "acc-ana", conversation: String = conv.serverId, epoch: Long = 0) =
        ServerEvent.ConvEphemeral(conversation, epoch, from, "dev", bytes)

    private suspend fun Pair2.receive(frame: ServerEvent.ConvEphemeral): EphemeralEvent? {
        val got = mutableListOf<EphemeralEvent>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { bob.events.toList(got) }
        bob.onFrame(frame)
        job.cancel()
        return got.singleOrNull()
    }

    private fun Pair2.assertNoMlsCalls() {
        // Counted from after the conversation was set up: sending and receiving signals adds no engine call.
        assertEquals(baseline, listOf(rig.ana1, rig.bob1).map { it.mls.processCalls to it.mls.processApplicationCalls })
        assertEquals(emptyList(), rig.ana1.mls.unlockedCalls, "no engine call outside the lock, so none at all")
        assertEquals(emptyList(), rig.bob1.mls.unlockedCalls)
    }

    @Test fun typingRoundTripsAsPlainJson() = runTest {
        val p = setup()
        assertTrue(p.ana.send("acc-bob", ContentEnvelope.Typing("t1", NOW, true)))
        val s = p.sent.single()
        assertEquals(p.conv.serverId, s.conversationId)
        assertTrue(String(s.bytes).contains("\"k\":\"typing\""), "the frame holds the envelope JSON")
        val event = p.receive(p.frame(s.bytes))!!
        assertEquals("acc-ana", event.chatId); assertEquals("acc-ana", event.accountId)
        assertEquals(true, assertIs<ContentEnvelope.Typing>(event.body).on)
        p.assertNoMlsCalls()
        p.rig.assertLockDiscipline()
    }

    @Test fun presenceRoundTrips() = runTest {
        val p = setup()
        assertTrue(p.ana.send("acc-bob", ContentEnvelope.Presence("p1", NOW, "online")))
        assertEquals("online", assertIs<ContentEnvelope.Presence>(p.receive(p.frame(p.sent.single().bytes))!!.body).state)
        p.assertNoMlsCalls()
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

    private fun typing() = ContentCodec.encode(ContentEnvelope.Typing("t", NOW, true))

    @Test fun framesThatDoNotQualifyAreDropped() = runTest {
        val p = setup()
        assertNull(p.receive(p.frame(typing(), conversation = "no-such")), "unknown conversation")
        assertNull(p.receive(p.frame(typing(), from = "acc-eve")), "an account that is not in the chat")
        assertNull(p.receive(p.frame(typing(), from = "acc-bob")), "my own account")
        assertNull(p.receive(p.frame(ContentCodec.encode(ContentEnvelope.Text("x", NOW, "I am a message")))), "a text envelope")
        assertNull(p.receive(p.frame("not json".encodeToByteArray())), "bad JSON")
        assertNull(p.receive(p.frame(ByteArray(0))), "empty")
        assertNull(p.receive(p.frame("""{"v":1,"id":"p","at":1,"k":"presence","state":"busy"}""".encodeToByteArray())), "bad presence")
        val padded = ("""{"v":1,"id":"t","at":1,"k":"typing","on":true,"x":"""" + "a".repeat(300) + """"}""").encodeToByteArray()
        assertNull(p.receive(p.frame(padded)), "over 256 bytes")
        // Exactly 256 bytes is fine, and the epoch is ignored.
        val head = """{"v":1,"id":"t","at":1,"k":"typing","on":true,"x":""""
        val body = (head + "a".repeat(256 - head.length - 2) + """"}""").encodeToByteArray()
        assertEquals(256, body.size)
        assertIs<ContentEnvelope.Typing>(p.receive(p.frame(body, epoch = 99))!!.body)
        assertNull(p.receive(p.frame((String(body) + " ").encodeToByteArray())), "257 bytes")
        p.assertNoMlsCalls()
    }

    @Test fun aBlockedPeerIsDropped() = runTest {
        val p = setup()
        p.rig.bob1.repo.block("acc-ana", NOW)
        assertNull(p.receive(p.frame(typing())))
    }

    @Test fun nothingIsStoredAndStoredMessagesDecryptNormally() = runTest {
        val p = setup()
        val bob = p.rig.bob1
        p.rig.ana1.send(p.conv, ContentEnvelope.Text("m1", NOW, "first"))
        p.ana.send("acc-bob", ContentEnvelope.Typing("t1", NOW, true))
        p.rig.ana1.send(p.conv, ContentEnvelope.Text("m2", NOW + 1, "second"))
        val processedBefore = bob.repo.processedCount()
        p.sent.forEach { assertIs<ContentEnvelope.Typing>(p.receive(p.frame(it.bytes))!!.body) }
        assertEquals(processedBefore, bob.repo.processedCount())
        assertTrue(bob.repo.messages("acc-ana", null, 10).isEmpty())
        bob.processor.sync()
        assertEquals(listOf("m1", "m2"), bob.repo.messages("acc-ana", null, 10).map { it.id })
        assertEquals(2, bob.repo.chat("acc-ana")!!.unread)
    }
}
