package org.example.app.messaging.sync

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.example.app.contracts.GroupId
import org.example.app.contracts.InboxItem
import org.example.app.contracts.MlsException
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.ports.PayloadHash

class InboxProcessorTest {
    /** ana1 creates the conversation with bob, bob1 joins it. */
    private suspend fun joinedRig(): Pair<Rig, ConversationRecord> {
        val rig = Rig().start()
        val record = rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        return rig to record
    }

    private fun InboxItem.copy(
        cursor: Long = this.cursor, conversationId: String = this.conversationId, senderAccountId: String = this.senderAccountId,
        senderDeviceId: String = this.senderDeviceId, payload: ByteArray = this.payload,
    ) = InboxItem(cursor, kind, conversationId, seq, epoch, senderAccountId, senderDeviceId, payload, sentAt)

    @Test
    fun rawStoredBeforeAck() = runTest {
        val (rig, record) = joinedRig()
        rig.ana1.send(record, ContentEnvelope.Text("m-1", NOW, "one"))
        rig.ana1.send(record, ContentEnvelope.Text("m-2", NOW + 1, "two"))
        val stored = mutableListOf<Long>()
        rig.bob1.repo.onInsertRaw = { items -> stored += items.map { it.cursor } }
        val acked = mutableListOf<List<Long>>()
        val previous = rig.server.onCall
        rig.server.onCall = { identity, call ->
            previous?.invoke(identity, call)
            if (identity == "acc-bob/bob1" && call == "ack") acked += stored.toList()
        }
        val waiting = rig.server.pending("acc-bob/bob1").map { it.cursor }
        assertEquals(2, rig.bob1.processor.sync())
        assertEquals(listOf(waiting), acked, "every item was stored before the ack")
        assertTrue(rig.server.pending("acc-bob/bob1").isEmpty())
        assertEquals(waiting.max().toString(), rig.bob1.repo.get(InboxProcessor.CURSOR_KEY)?.decodeToString())
        assertTrue(rig.bob1.repo.rawItems(10).isEmpty())
        val texts = rig.bob1.repo.messages("acc-ana", null, 10)
        assertEquals(listOf("m-1", "m-2"), texts.map { it.id })
        assertEquals("delivered", texts[0].status)
        assertEquals(2, rig.bob1.repo.chat("acc-ana")?.unread)
        rig.assertLockDiscipline()
    }

    @Test
    fun syncPagesPast100() = runTest {
        val (rig, record) = joinedRig()
        repeat(250) { rig.ana1.send(record, ContentEnvelope.Text("m-%03d".format(it), NOW + it, "x")) }
        assertEquals(250, rig.bob1.processor.sync())
        assertEquals(250, rig.bob1.repo.messages("acc-ana", null, 1000).size)
    }

    @Test
    fun processedTwiceIsSkippedButAcked() = runTest {
        val (rig, record) = joinedRig()
        rig.ana1.send(record, ContentEnvelope.Text("m-1", NOW, "one"))
        val item = rig.server.pending("acc-bob/bob1").single()
        rig.bob1.repo.markProcessed(PayloadHash.of(item.payload), NOW)
        val before = rig.bob1.mls.processCalls
        rig.bob1.processor.sync()
        assertEquals(before, rig.bob1.mls.processCalls)
        assertTrue(rig.server.pending("acc-bob/bob1").isEmpty(), "acked")
        assertTrue(rig.bob1.repo.rawItems(10).isEmpty())
        assertTrue(rig.bob1.repo.messages("acc-ana", null, 10).isEmpty())

        // The same payload again under a new cursor (a re-delivery after a crash): no second row, no second process.
        rig.ana1.send(record, ContentEnvelope.Text("m-2", NOW, "two"))
        val second = rig.server.pending("acc-bob/bob1").single()
        rig.bob1.processor.sync()
        rig.server.inject("acc-bob/bob1") { second.copy(cursor = it) }
        val calls = rig.bob1.mls.processCalls
        rig.bob1.processor.sync()
        assertEquals(calls, rig.bob1.mls.processCalls)
        assertEquals(listOf("m-2"), rig.bob1.repo.messages("acc-ana", null, 10).map { it.id })
        rig.assertLockDiscipline()
    }

    @Test
    fun echoedOwnCommitSkipped() = runTest {
        val rig = Rig().start()
        val record = rig.ana1.manager.create("acc-bob")
        val echo = rig.server.pending("acc-ana/ana1").single { it.kind == "commit" }
        rig.ana1.processor.sync()
        assertEquals(0, rig.ana1.mls.processCalls)
        assertTrue(rig.ana1.repo.isProcessed(PayloadHash.of(echo.payload)))
        assertTrue(rig.ana1.repo.rawItems(10).isEmpty())
        assertEquals(1L, rig.ana1.repo.conversation(record.serverId)?.epoch)
        rig.assertLockDiscipline()
    }

    /** A crash between the server's 200 and `commitAccepted`: the stored epoch is behind, so the echo merges the pending commit. */
    @Test
    fun echoAfterCrashMergesPendingCommit() = runTest {
        val rig = Rig().start()
        val ana = rig.ana1
        val theirs = ana.api.claimKeyPackages("acc-bob")
        val created = ana.api.createConversation("acc-bob")
        val group = ana.lock.withLock { ana.mls.createGroup() }
        val bundle = ana.lock.withLock { ana.mls.add(theirs.values.toList(), group) }
        ana.api.postCommit(created.conversationId, 0, bundle.commit, bundle.welcome, theirs.keys.toList(), emptyList())
        ana.repo.upsertChat(org.example.app.messaging.ports.ChatRecord("acc-bob", false, 0, false, NOW))
        ana.repo.upsertConversation(ConversationRecord(created.conversationId, "acc-bob", group.raw, "e2ee", "e2ee", false, 0, true, NOW))
        ana.processor.sync()
        assertEquals(1, ana.mls.processCalls)
        assertEquals(1L, ana.mls.epoch(group))
        assertEquals(1L, ana.repo.conversation(created.conversationId)?.epoch)
        assertEquals(listOf("acc-ana/ana1", "acc-bob/bob1", "acc-bob/bob2"), ana.repo.members(created.conversationId).map { it.identity })
        rig.assertLockDiscipline()
    }

    @Test
    fun modePinnedFromFirstEnvelope() = runTest {
        val rig = Rig().start()
        val record = rig.ana1.manager.create("acc-bob")
        // Only the Welcome first: joined, not pinned yet.
        val items = rig.server.pending("acc-bob/bob1")
        val welcome = items.single { it.kind == "welcome" }
        rig.server.rewrite("acc-bob/bob1") { if (it.kind == "welcome") it else it.copy(cursor = it.cursor + 1000) }
        rig.bob1.processor.sync(maxItems = 1)
        val joined = assertNotNull(rig.bob1.repo.conversation(record.serverId))
        assertEquals(null, joined.innerMode)
        assertEquals("e2ee", joined.mode)
        assertTrue(rig.bob1.repo.isProcessed(PayloadHash.of(welcome.payload)))

        rig.bob1.processor.sync()
        val pinned = assertNotNull(rig.bob1.repo.conversation(record.serverId))
        assertEquals("e2ee", pinned.innerMode)
        assertFalse(pinned.modeMismatch)
        assertTrue(rig.bob1.repo.allMessages().isEmpty())
        rig.assertLockDiscipline()
    }

    @Test
    fun laterDifferentModeSetsMismatch() = runTest {
        val (rig, record) = joinedRig()
        rig.ana1.send(record, ContentEnvelope.Mode("mode-2", NOW, "server_readable"))
        rig.ana1.send(record, ContentEnvelope.Mode("mode-3", NOW, "server_readable"))
        rig.bob1.processor.sync()
        val conversation = assertNotNull(rig.bob1.repo.conversation(record.serverId))
        assertEquals("e2ee", conversation.innerMode)
        assertTrue(conversation.modeMismatch)
        val lines = rig.bob1.repo.allMessages()
        assertEquals(listOf("sys-${record.serverId}-messagesPaused"), lines.map { it.id })
        assertEquals("system", lines.single().senderAccount)
        assertEquals("""{"messagesPaused":{}}""", lines.single().envelope.decodeToString())
    }

    @Test
    fun senderFromCredentialNotServer() = runTest {
        val (rig, record) = joinedRig()
        rig.ana1.send(record, ContentEnvelope.Text("m-1", NOW, "hello"))
        rig.server.rewrite("acc-bob/bob1") { it.copy(senderAccountId = "acc-mallory", senderDeviceId = "mal1", conversationId = "conv-9999") }
        rig.bob1.processor.sync()
        val message = assertNotNull(rig.bob1.repo.message("m-1"))
        assertEquals("acc-ana", message.senderAccount)
        assertEquals("ana1", message.senderDevice)
        assertEquals("acc-ana", message.chatId)
        assertEquals(record.serverId, message.conversationId, "the conversation comes from the MLS group, not the server's label")
        assertEquals("hello", message.folded)
        assertEquals(null, rig.bob1.repo.chat("acc-mallory"))
    }

    @Test
    fun editAndDeleteOnlyBySender() = runTest {
        val (rig, record) = joinedRig()
        rig.ana1.processor.sync()
        rig.ana1.send(record, ContentEnvelope.Text("m-1", NOW, "hello", r = "x"))
        rig.bob1.processor.sync()
        val bobRecord = assertNotNull(rig.bob1.repo.conversation(record.serverId))
        rig.bob1.send(bobRecord, ContentEnvelope.Edit("e-1", NOW + 1, "m-1", "forged"))
        rig.bob1.send(bobRecord, ContentEnvelope.Delete("d-1", NOW + 1, "m-1"))
        rig.ana1.send(record, ContentEnvelope.Edit("e-2", NOW + 2, "m-1", "Edited"))
        rig.bob2.processor.sync()
        val edited = assertNotNull(rig.bob2.repo.message("m-1"))
        assertFalse(edited.deleted)
        assertEquals(ContentEnvelope.Text("m-1", NOW, "Edited", r = "x"), ContentCodec.decode(edited.envelope))
        assertEquals(NOW + 2, edited.editedAt)
    }

    @Test
    fun undecryptablePlaceholder() = runTest {
        val (rig, record) = joinedRig()
        rig.server.inject("acc-bob/bob1") {
            InboxItem(it, "message", record.serverId, 9, 1, "acc-ana", "ana1", "garbage".toByteArray(), Instant.ofEpochMilli(NOW))
        }
        val garbageCursor = rig.server.pending("acc-bob/bob1").single().cursor
        rig.ana1.send(record, ContentEnvelope.Text("m-1", NOW, "after"))
        rig.bob1.processor.sync()
        val placeholder = assertNotNull(rig.bob1.repo.message("undecryptable-$garbageCursor"))
        assertTrue(placeholder.undecryptable)
        assertNotNull(rig.bob1.repo.message("m-1"))
        val count = rig.bob1.repo.allMessages().size
        rig.bob1.processor.drain()
        rig.bob1.processor.sync()
        assertEquals(count, rig.bob1.repo.allMessages().size)
    }

    @Test
    fun temporaryEngineErrorStopsWithoutPlaceholder() = runTest {
        val (rig, record) = joinedRig()
        rig.ana1.send(record, ContentEnvelope.Text("m-1", NOW, "one"))
        rig.ana1.send(record, ContentEnvelope.Text("m-2", NOW, "two"))
        rig.bob1.mls.failProcess = { MlsException.Storage("disk") }
        rig.bob1.processor.sync()
        assertEquals(2, rig.bob1.repo.rawItems(10).size, "kept for the next drain, in order")
        assertTrue(rig.bob1.repo.allMessages().isEmpty())
        rig.bob1.mls.failProcess = null
        rig.bob1.processor.drain()
        assertEquals(listOf("m-1", "m-2"), rig.bob1.repo.messages("acc-ana", null, 10).map { it.id })
    }

    /** The engine accepted the item but the database write failed: the retry writes the same result without a second `process`. */
    /** Fails the first transaction after bob1's next ack, so the fetch is stored and the drain's first write fails. */
    private fun failFirstWriteAfterAck(rig: Rig) {
        val previous = rig.server.onCall
        var armed = true
        rig.server.onCall = { identity, call ->
            previous?.invoke(identity, call)
            if (armed && identity == "acc-bob/bob1" && call == "ack") { armed = false; rig.bob1.repo.failNextTransactions = 1 }
        }
    }

    @Test
    fun failedWriteRetriesWithoutSecondProcess() = runTest {
        val (rig, record) = joinedRig()
        rig.ana1.send(record, ContentEnvelope.Text("m-1", NOW, "one"))
        failFirstWriteAfterAck(rig)
        val calls = rig.bob1.mls.processCalls
        rig.bob1.processor.sync()
        assertEquals(1, rig.bob1.repo.rawItems(10).size)
        assertEquals(null, rig.bob1.repo.message("m-1"))
        rig.bob1.processor.drain()
        assertEquals(calls + 1, rig.bob1.mls.processCalls)
        assertNotNull(rig.bob1.repo.message("m-1"))
        assertEquals(1, rig.bob1.repo.chat("acc-ana")?.unread)
    }

    @Test
    fun welcomeWriteFailureRetriesWithoutSecondProcess() = runTest {
        val rig = Rig().start()
        val record = rig.ana1.manager.create("acc-bob")
        failFirstWriteAfterAck(rig)
        rig.bob1.processor.sync()
        assertEquals(null, rig.bob1.repo.conversation(record.serverId))
        val calls = rig.bob1.mls.processCalls
        rig.bob1.processor.drain()
        assertEquals(calls + 1, rig.bob1.mls.processCalls, "only the mode envelope is processed now")
        assertEquals("e2ee", rig.bob1.repo.conversation(record.serverId)?.innerMode)
    }

    /** Cancelling a sync while the engine runs still writes the rows of that item (the step is not cancellable). */
    @Test
    fun cancelDuringProcessStillWritesRows() = runTest {
        val (rig, record) = joinedRig()
        rig.ana1.send(record, ContentEnvelope.Text("m-1", NOW, "one"))
        val gate = CompletableDeferred<Unit>()
        val ran = CompletableDeferred<Unit>()
        rig.bob1.mls.holdProcess = gate
        rig.bob1.mls.processRan = ran
        val job = launch { rig.bob1.processor.sync() }
        ran.await()
        job.cancel()
        yield()
        gate.complete(Unit)
        job.join()
        assertNotNull(rig.bob1.repo.message("m-1"))
        assertTrue(rig.bob1.repo.rawItems(10).isEmpty())
        assertTrue(rig.bob1.repo.isProcessed(PayloadHash.of(rig.server.messages.last().payload)))
    }

    @Test
    fun commitFromPeerUpdatesEpochAndMembers() = runTest {
        val (rig, record) = joinedRig()
        rig.bob1.processor.sync()
        val bobRecord = assertNotNull(rig.bob1.repo.conversation(record.serverId))
        val group = GroupId(bobRecord.groupId)
        rig.bob1.manager.postCommitWithRetry(record.serverId, group, emptyList(), listOf("bob2")) {
            rig.bob1.mls.remove(listOf("acc-bob/bob2"), group)
        }
        rig.ana1.processor.sync()
        assertEquals(2L, rig.ana1.repo.conversation(record.serverId)?.epoch)
        assertEquals(listOf("acc-ana/ana1", "acc-ana/ana2", "acc-bob/bob1"), rig.ana1.repo.members(record.serverId).map { it.identity })
        rig.bob2.processor.sync()
        assertEquals(false, rig.bob2.repo.conversation(record.serverId)?.active, "removed: inactive")
        rig.assertLockDiscipline()
    }

    @Test
    fun welcomeWithUnknownServerModeIsDroppedNotRetriedForever() = runTest {
        val rig = Rig().start()
        val record = rig.ana1.manager.create("acc-bob")
        rig.server.conversation(record.serverId)!!.mode = "plaintext"
        rig.bob1.processor.sync()
        assertEquals(null, rig.bob1.repo.conversation(record.serverId))
        assertTrue(rig.bob1.repo.rawItems(10).isEmpty(), "nothing blocks the queue")
    }

    @Test
    fun callSignalNotStored() = runTest {
        val (rig, record) = joinedRig()
        val payload = org.example.app.contracts.CallSignalPayload(org.example.app.contracts.CallSignalPayload.Kind.ring, "c-1", video = false)
        rig.ana1.send(record, ContentEnvelope.CallSig("s-1", NOW, payload))
        val applied = mutableListOf<AppliedResult>()
        rig.bob1.processor.onApplied = { applied += it }
        rig.bob1.processor.sync()
        val signal = applied.filterIsInstance<AppliedResult.CallSignal>().single()
        assertEquals("acc-ana/ana1", signal.sender)
        assertTrue(rig.bob1.repo.allMessages().isEmpty())
    }
}
