package org.example.app.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.NotificationOutcome
import org.example.app.contracts.RingOutcome
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.content.ReceiptKind
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.ports.RawInboxRecord
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.TestDevice

fun pad(payload: ByteArray): ByteArray {
    val size = intArrayOf(256, 512, 1024, 2048, 3072).first { it >= payload.size + 2 }
    val out = ByteArray(size)
    out[0] = (payload.size shr 8).toByte(); out[1] = payload.size.toByte()
    payload.copyInto(out, 2)
    return out
}

fun TestDevice.notifications(ended: EndedCalls = EndedCalls { null }) =
    NotificationProcessor(me, mls, repo, clock, handler, membership, sealer(), processor, ended)

class NotificationProcessorTest {
    private class Setup(val rig: Rig, val conv: ConversationRecord, val np: NotificationProcessor)

    private suspend fun setup(contact: Boolean, ended: EndedCalls = EndedCalls { null }): Setup {
        val rig = Rig().start()
        if (contact) rig.bob1.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        val conv = rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        return Setup(rig, conv, rig.bob1.notifications(ended))
    }

    /** What the push handler returns for the next item ana sends. The dispatcher holds the MLS lock around the call. */
    private suspend fun Setup.push(env: ContentEnvelope): NotificationOutcome {
        rig.ana1.send(conv, env)
        val item = rig.server.pending("acc-bob/bob1").last()
        return rig.bob1.lock.withLock { np.onMessagePush(pad(item.payload)) }
    }

    @Test
    fun notificationOutcomes() = runTest {
        val s = setup(contact = true)
        val message = s.push(ContentEnvelope.Text("m-1", NOW, "hello there"))
        assertIs<NotificationOutcome.Message>(message)
        assertEquals("Ana", message.senderName); assertEquals("hello there", message.preview); assertEquals("acc-ana", message.chatId)

        assertIs<NotificationOutcome.Nothing>(s.push(ContentEnvelope.Receipt("r-1", NOW, ReceiptKind.Delivered, listOf("x"))))
        assertIs<NotificationOutcome.Nothing>(s.push(ContentEnvelope.Edit("e-1", NOW, "m-1", "changed")))
        assertIs<NotificationOutcome.Nothing>(s.push(ContentEnvelope.React("x-1", NOW, "m-1", "+")))
        assertIs<NotificationOutcome.Nothing>(s.push(ContentEnvelope.Delete("d-1", NOW, "m-1")))

        s.rig.bob1.repo.upsertChat(s.rig.bob1.repo.chat("acc-ana")!!.copy(muted = true))
        assertIs<NotificationOutcome.Nothing>(s.push(ContentEnvelope.Text("m-2", NOW, "muted")))
        s.rig.bob1.repo.upsertChat(s.rig.bob1.repo.chat("acc-ana")!!.copy(muted = false))
        s.rig.bob1.repo.block("acc-ana", NOW)
        assertIs<NotificationOutcome.Nothing>(s.push(ContentEnvelope.Text("m-3", NOW, "blocked")))
        s.rig.assertLockDiscipline()
    }

    @Test
    fun strangerIsARequest() = runTest {
        val s = setup(contact = false)
        val outcome = s.push(ContentEnvelope.Text("m-1", NOW, "secret text"))
        assertIs<NotificationOutcome.Request>(outcome)
        assertEquals("acc-ana", outcome.chatId)
    }

    @Test
    fun undecryptableEmptyAndMalformed() = runTest {
        val s = setup(contact = true)
        val bob = s.rig.bob1
        assertIs<NotificationOutcome.Generic>(bob.lock.withLock { s.np.onMessagePush(pad("not an mls message".encodeToByteArray())) })
        assertIs<NotificationOutcome.FetchInbox>(bob.lock.withLock { s.np.onMessagePush(pad(ByteArray(0))) })
        assertIs<NotificationOutcome.Generic>(bob.lock.withLock { s.np.onMessagePush(ByteArray(300)) }) // not a bucket size
        val badPad = pad("x".encodeToByteArray()).also { it[100] = 1 }
        assertIs<NotificationOutcome.Generic>(bob.lock.withLock { s.np.onMessagePush(badPad) })
    }

    @Test
    fun pushIsStoredOnceAndTheInboxSkipsIt() = runTest {
        val s = setup(contact = true)
        s.push(ContentEnvelope.Text("m-1", NOW, "hello"))
        assertEquals(1, s.rig.bob1.repo.messages("acc-ana", null, 10).count { it.id == "m-1" })
        assertEquals(1, s.rig.bob1.repo.chat("acc-ana")!!.unread)
        s.rig.bob1.processor.sync()
        assertEquals(1, s.rig.bob1.repo.messages("acc-ana", null, 10).count { it.id == "m-1" })
        assertEquals(1, s.rig.bob1.repo.chat("acc-ana")!!.unread)
        assertTrue(s.rig.bob1.repo.rawItems(10).isEmpty())
    }

    @Test
    fun fetchTakesTheLockPerDecryptAndNeverHoldsItOverTheNetwork() = runTest {
        val s = setup(contact = true)
        s.rig.ana1.send(s.conv, ContentEnvelope.Text("m-1", NOW, "via fetch"))
        val outcomes = s.np.fetchOutcomes()
        val m = assertIs<NotificationOutcome.Message>(outcomes.single())
        assertEquals("via fetch", m.preview)
        s.rig.assertLockDiscipline()
    }

    private val ring = CallSignalPayload(kind = CallSignalPayload.Kind.ring, callID = "call-9", video = true)

    private suspend fun Setup.ringPush(): RingOutcome {
        val sealed = rig.ana1.sealer().seal(ring, "acc-bob")
        return rig.bob1.lock.withLock { np.onCallPush(pad(sealed)) }
    }

    @Test
    fun ringFromContactRings() = runTest {
        val s = setup(contact = true)
        val r = assertIs<RingOutcome.Ring>(s.ringPush())
        assertEquals("call-9", r.callId); assertEquals("acc-ana", r.fromAccountId); assertEquals("Ana", r.callerName); assertTrue(r.video)
        s.rig.assertLockDiscipline()
    }

    @Test
    fun strangerRingIsSilentMissed() = runTest {
        val s = setup(contact = false)
        val r = assertIs<RingOutcome.SilentMissed>(s.ringPush())
        assertEquals("acc-ana", r.fromAccountId); assertEquals("call-9", r.callId)
    }

    @Test
    fun missedBeforeAnswerOnlyWhenNobodyAnswered() = runTest {
        for ((end, expectMissed) in listOf(
            EndedCall("hangup", answered = false) to true,
            EndedCall("missed", answered = false) to true,
            EndedCall("answered_elsewhere", answered = true) to false,
            EndedCall("declined", answered = false) to false,
            EndedCall("hangup", answered = true) to false,
        )) {
            val s = setup(contact = true, ended = { end })
            val r = s.ringPush()
            assertEquals(expectMissed, r is RingOutcome.MissedBeforeAnswer, "${end.reason} answered=${end.answered}")
            // Answered or declined elsewhere shows nothing at all (design: a ring that is over is dropped silently).
            if (!expectMissed) assertIs<RingOutcome.Ignore>(r, "${end.reason} answered=${end.answered}")
            if (r is RingOutcome.MissedBeforeAnswer) assertEquals("acc-ana", r.chatId)
            assertTrue(r !is RingOutcome.Ring)
        }
    }

    @Test
    fun blockedContactRingShowsNothing() = runTest {
        val s = setup(contact = true)
        s.rig.bob1.repo.block("acc-ana", NOW)
        assertIs<RingOutcome.Ignore>(s.ringPush())
    }

    @Test
    fun blockedStrangerRingShowsNothing() = runTest {
        val s = setup(contact = false)
        s.rig.bob1.repo.block("acc-ana", NOW)
        assertIs<RingOutcome.Ignore>(s.ringPush())
    }

    @Test
    fun fetchFailureWithNothingFetchedPropagatesSoTheRunnerRetries() = runTest {
        val s = setup(contact = true)
        s.rig.server.failNextInboxReads = 1
        assertFailsWith<Exception> { s.np.fetchOutcomes() }
    }

    @Test
    fun fetchFailureThrowsEvenWhenLeftoverRowsFillTheResults() = runTest {
        val s = setup(contact = true)
        s.rig.ana1.send(s.conv, ContentEnvelope.Text("m-left", NOW, "left over"))
        val item = s.rig.server.pending("acc-bob/bob1").last()
        // A raw row an earlier run stored but did not drain: this run's drain reports it.
        s.rig.bob1.repo.insertRaw(listOf(RawInboxRecord(
            cursor = 9_000, kind = "message", conversationId = s.conv.serverId, seq = null, epoch = 0,
            senderAccount = "acc-ana", senderDevice = "ana1", payload = item.payload, sentAt = NOW,
        )))
        s.rig.server.failNextInboxReads = 1
        assertFailsWith<Exception> { s.np.fetchOutcomes() }
        assertNotNull(s.rig.bob1.repo.message("m-left"), "the leftover row was still drained")
    }

    @Test
    fun undecryptableRingDrops() = runTest {
        val s = setup(contact = true)
        assertIs<RingOutcome.Drop>(s.rig.bob1.lock.withLock { s.np.onCallPush(pad("junk".encodeToByteArray())) })
        assertIs<RingOutcome.Drop>(s.rig.bob1.lock.withLock { s.np.onCallPush(pad(ByteArray(0))) })
    }
}
