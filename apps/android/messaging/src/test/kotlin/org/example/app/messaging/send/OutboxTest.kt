package org.example.app.messaging.send

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.app.contracts.DeliveryApi
import org.example.app.contracts.HpkeInfo
import org.example.app.contracts.ServerException
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.content.ReceiptKind
import org.example.app.messaging.content.TextLimit
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.SimulatedKill
import org.example.app.messaging.testing.SoftwareHpke

class OutboxTest {
    /** ana1 created the conversation, bob1 and ana2 joined it. */
    private suspend fun joined(mode: String = "e2ee"): Pair<Rig, ConversationRecord> {
        val rig = Rig(mode).start()
        val record = rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        rig.ana2.processor.sync()
        return rig to record
    }

    private fun Rig.posted(id: String) = server.messages.filter { it.clientMsgId == id }

    private fun text(id: String, t: String = "hi") = ContentEnvelope.Text(id, NOW, t)

    @Test
    fun flushSendsQueuedMessageOnceAndMarksItSent() = runTest {
        val (rig, record) = joined()
        rig.ana1.outbox.enqueue(text("m-1"), "acc-bob")
        assertEquals("sending", rig.ana1.repo.message("m-1")?.status)
        rig.ana1.outbox.flush()
        assertEquals(1, rig.posted("m-1").size)
        assertNull(rig.posted("m-1").single().escrow)
        assertEquals("sent", rig.ana1.repo.message("m-1")?.status)
        assertTrue(rig.ana1.repo.dueOutbox(Long.MAX_VALUE).isEmpty())
        rig.bob1.processor.sync()
        assertEquals("m-1", rig.bob1.repo.messages("acc-ana", null, 10).single { !it.undecryptable }.id)
        assertEquals(record.serverId, rig.posted("m-1").single().conversationId)
        rig.assertLockDiscipline()
    }

    // Crash points

    @Test
    fun killedBeforeTheSendIsSentAfterRestart() = runTest {
        val (rig, _) = joined()
        rig.ana1.outbox.enqueue(text("m-1"), "acc-bob")
        val previous = rig.server.onCall
        rig.server.onCall = { identity, call ->
            previous?.invoke(identity, call)
            if (call == "postMessage" && identity == "acc-ana/ana1") throw SimulatedKill()
        }
        assertFailsWith<SimulatedKill> { rig.ana1.outbox.flush() }
        assertEquals(0, rig.posted("m-1").size)
        assertEquals("sending", rig.ana1.repo.message("m-1")?.status)
        assertEquals(1, rig.ana1.repo.dueOutbox(Long.MAX_VALUE).size)

        rig.server.onCall = previous
        rig.ana1.restart()
        rig.ana1.outbox.flush()
        assertEquals(1, rig.posted("m-1").size)
        assertEquals("sent", rig.ana1.repo.message("m-1")?.status)
        assertTrue(rig.ana1.repo.dueOutbox(Long.MAX_VALUE).isEmpty())
        rig.assertLockDiscipline()
    }

    @Test
    fun killedAfterTheSendBeforeTheAckSendsNothingTwice() = runTest {
        val (rig, _) = joined()
        rig.ana1.outbox.enqueue(text("m-1"), "acc-bob")
        rig.server.afterMessageApplied = { throw SimulatedKill() }
        assertFailsWith<SimulatedKill> { rig.ana1.outbox.flush() }
        // The server has it; this device does not know.
        assertEquals(1, rig.posted("m-1").size)
        assertEquals("sending", rig.ana1.repo.message("m-1")?.status)
        assertEquals(1, rig.ana1.repo.dueOutbox(Long.MAX_VALUE).size)

        rig.server.afterMessageApplied = null
        rig.ana1.restart()
        rig.ana1.outbox.flush()
        // Same client message ID: the server keeps one copy, and the outbox is settled.
        assertEquals(1, rig.posted("m-1").size)
        assertEquals("sent", rig.ana1.repo.message("m-1")?.status)
        assertTrue(rig.ana1.repo.dueOutbox(Long.MAX_VALUE).isEmpty())
        rig.bob1.processor.sync()
        assertEquals(listOf("m-1"), rig.bob1.repo.messages("acc-ana", null, 10).filter { !it.undecryptable }.map { it.id })
        rig.assertLockDiscipline()
    }

    @Test
    fun duplicateAckChangesNothing() = runTest {
        val (rig, _) = joined()
        rig.ana1.outbox.enqueue(text("m-1"), "acc-bob")
        rig.ana1.outbox.flush()
        // The UI queues the same envelope again (a double tap, a replayed intent): one message, one server copy.
        rig.ana1.outbox.enqueue(text("m-1"), "acc-bob")
        rig.ana1.outbox.flush()
        assertEquals(1, rig.posted("m-1").size)
        assertEquals(1, rig.ana1.repo.messages("acc-bob", null, 50).count { it.id == "m-1" })
        assertEquals("sent", rig.ana1.repo.message("m-1")?.status)
        // A second completion never lowers a status that moved on.
        rig.ana1.repo.updateStatus(listOf("m-1"), "read")
        rig.ana1.repo.completeSend("m-1", "sent")
        assertEquals("read", rig.ana1.repo.message("m-1")?.status)
        assertTrue(rig.ana1.repo.dueOutbox(Long.MAX_VALUE).isEmpty())
    }

    // Order and backoff

    @Test
    fun newerMessageNeverOvertakesAnOlderOneThatIsBackingOff() = runTest {
        val (rig, _) = joined()
        rig.server.failNextMessages = 1
        rig.ana1.outbox.enqueue(text("m-1"), "acc-bob")
        rig.ana1.outbox.enqueue(text("m-2"), "acc-bob")
        rig.ana1.outbox.flush()
        assertEquals(0, rig.posted("m-1").size + rig.posted("m-2").size)
        val head = rig.ana1.repo.dueOutbox(Long.MAX_VALUE).first { it.messageId == "m-1" }
        assertEquals(1, head.attempts)
        assertTrue(head.nextAttemptAt > NOW)
        assertEquals("sending", rig.ana1.repo.message("m-2")?.status)
    }

    @Test
    fun manualRetryOfAFailedMessageGoesToTheEnd() = runTest {
        val (rig, _) = joined()
        rig.ana1.outbox.enqueue(text("m-1"), "acc-bob")
        rig.ana1.repo.completeSend("m-1", "failed")
        rig.ana1.outbox.enqueue(text("m-2"), "acc-bob")
        assertTrue(rig.ana1.outbox.retry("m-1"))
        // Same instant for both rows: the order is the queue's, and the retried message is behind m-2.
        assertEquals("m-2", rig.ana1.repo.dueOutboxHeads(NOW).single().messageId)
        assertEquals("sending", rig.ana1.repo.message("m-1")?.status)
    }

    @Test
    fun composedTextIsCutAt4096Graphemes() = runTest {
        val (rig, _) = joined()
        val long = "ab".repeat(3000)
        rig.ana1.outbox.enqueue(text("m-1", long), "acc-bob")
        val stored = org.example.app.messaging.content.ContentCodec.decode(rig.ana1.repo.message("m-1")!!.envelope) as ContentEnvelope.Text
        assertEquals(TextLimit.truncate(long), stored.t)
        assertEquals(4096, stored.t.length)
    }

    // Escrow

    @Test
    fun noEscrowWhenPinnedE2ee() = runTest {
        val (rig, record) = joined("e2ee")
        // The server now claims server_readable; the creator's pinned mode is e2ee.
        rig.ana1.repo.setServerMode(record.serverId, "server_readable")
        rig.ana1.outbox.enqueue(text("m-1"), "acc-bob")
        rig.ana1.outbox.flush()

        assertEquals(0, rig.posted("m-1").size)
        assertTrue(rig.server.messages.all { it.escrow == null })
        assertEquals("failed", rig.ana1.repo.message("m-1")?.status)
        assertTrue(rig.ana1.repo.conversation(record.serverId)!!.modeMismatch)
        // One "messages paused" line, stored once; it names no encryption type.
        val line = rig.ana1.repo.message("sys-${record.serverId}-messagesPaused")
        assertNotNull(line)
        assertEquals("""{"messagesPaused":{}}""", line.envelope.decodeToString())
        // Sending stays paused for the next message too.
        rig.ana1.outbox.enqueue(text("m-2"), "acc-bob")
        rig.ana1.outbox.flush()
        assertEquals(0, rig.posted("m-2").size)
        assertEquals(1, rig.ana1.repo.messages("acc-bob", null, 50).count { it.id.endsWith("messagesPaused") })
    }

    @Test
    fun serverReadableChatSendsAnEscrowCopyThatTheServerKeyOpens() = runTest {
        val (rig, record) = joined("server_readable")
        rig.ana1.outbox.enqueue(text("m-1", "Lunch?"), "acc-bob")
        rig.ana1.outbox.flush()
        val escrow = assertNotNull(rig.posted("m-1").single().escrow)
        val plain = Json.parseToJsonElement(SoftwareHpke().open(rig.escrowKeys, HpkeInfo.ESCROW, escrow).decodeToString()).jsonObject
        assertEquals(record.serverId, plain["conversationID"]!!.jsonPrimitive.content)
        assertEquals("acc-ana", plain["senderAccountID"]!!.jsonPrimitive.content)
        assertEquals("Lunch?", plain["body"]!!.jsonObject["t"]!!.jsonPrimitive.content)
        rig.assertLockDiscipline()
    }

    @Test
    fun escrowRequiredRefreshesThenRetriesOnce() = runTest {
        val (rig, record) = joined("server_readable")
        // This device believes the server is e2ee (an old answer): it sends without a copy and the server refuses.
        rig.ana1.repo.setServerMode(record.serverId, "e2ee")
        val lookupsBefore = rig.server.calls.count { it == "acc-ana/ana1 conversation" }
        rig.ana1.outbox.enqueue(text("m-1"), "acc-bob")
        rig.ana1.outbox.flush()

        assertEquals("sent", rig.ana1.repo.message("m-1")?.status)
        assertNotNull(rig.posted("m-1").single().escrow)
        assertEquals(1, rig.server.calls.count { it == "acc-ana/ana1 conversation" } - lookupsBefore, "one mode refresh")
        assertEquals("server_readable", rig.ana1.repo.conversation(record.serverId)?.mode)
    }

    @Test
    fun escrowRequiredIsRetriedOnlyOnce() = runTest {
        val (rig, record) = joined("server_readable")
        var posts = 0
        val refusing = object : DeliveryApi by rig.ana1.api {
            override suspend fun postMessage(id: String, epoch: Long, ciphertext: ByteArray, escrow: ByteArray?, clientMsgId: String): Long {
                posts += 1
                throw ServerException(400, "escrow_required", null, null)
            }
        }
        val outbox = Outbox(rig.ana1.me, rig.ana1.mls, refusing, rig.ana1.repo, rig.ana1.lock, rig.ana1.clock, rig.ana1.manager, rig.ana1.escrow, syncOnce = {})
        val lookupsBefore = rig.server.calls.count { it == "acc-ana/ana1 conversation" }
        val failure = assertFailsWith<OutboxError.Rejected> { outbox.send(text("m-1"), rig.ana1.repo.conversation(record.serverId)!!) }
        assertEquals("escrow_required", failure.code)
        assertEquals(2, posts)
        assertEquals(1, rig.server.calls.count { it == "acc-ana/ana1 conversation" } - lookupsBefore)
    }

    @Test
    fun waitsForTheModeMessageWhenServerReadableAndNothingPinned() = runTest {
        val rig = Rig("server_readable").start()
        rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync(maxItems = 1) // the Welcome only; the mode message is still waiting
        val joined = rig.bob1.repo.preferredConversation("acc-ana")!!
        assertNull(joined.innerMode)
        rig.bob1.outbox.enqueue(text("m-1"), "acc-ana")
        rig.bob1.outbox.flush()
        assertEquals(0, rig.posted("m-1").size)
        assertEquals("sending", rig.bob1.repo.message("m-1")?.status)
        assertEquals(0, rig.bob1.repo.dueOutbox(Long.MAX_VALUE).single().attempts)

        rig.bob1.processor.sync() // the mode message arrives and pins
        rig.bob1.outbox.flush()
        assertEquals(1, rig.posted("m-1").size)
        assertNotNull(rig.posted("m-1").single().escrow)
    }

    // Control messages

    @Test
    fun queuedControlMessageStaysBehindEarlierMessages() = runTest {
        val (rig, _) = joined()
        rig.ana1.outbox.enqueue(text("m-1"), "acc-bob")
        rig.ana1.outbox.enqueueControl("acc-bob") { id, at -> ContentEnvelope.Delete(id, at, "m-1") }
        rig.ana1.outbox.flush()
        val order = rig.server.messages.filter { it.sender == "acc-ana/ana1" }.takeLast(2)
        assertEquals(2, order.size)
        rig.bob1.processor.sync()
        assertTrue(rig.bob1.repo.message("m-1")!!.deleted)
        assertTrue(rig.ana1.repo.dueOutbox(Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun sendControlSendsNowAndFailsLoudly() = runTest {
        val (rig, _) = joined()
        rig.ana1.outbox.sendControl("acc-bob") { id, at -> ContentEnvelope.Receipt(id, at, ReceiptKind.Read, listOf("x")) }
        rig.server.failNextMessages = 1
        assertFailsWith<OutboxError.Retryable> {
            rig.ana1.outbox.sendControl("acc-bob") { id, at -> ContentEnvelope.Receipt(id, at, ReceiptKind.Read, listOf("y")) }
        }
    }
}
