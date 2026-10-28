package org.example.app.messaging.send

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.content.LinkSnapshot
import org.example.app.messaging.content.ReceiptKind
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig

class ReceiptsTest {
    private suspend fun joined(): Rig {
        val rig = Rig().start()
        rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        return rig
    }

    private fun Rig.bobPosts() = server.messages.filter { it.sender == "acc-bob/bob1" }

    @Test
    fun deliveredReceiptPerBatch() = runTest {
        val rig = joined()
        rig.bob1.receipts.attach(rig.bob1.processor)
        for (i in 1..3) rig.ana1.outbox.enqueue(ContentEnvelope.Text("m-$i", NOW + i, "text $i"), "acc-bob")
        rig.ana1.outbox.flush()

        rig.bob1.processor.sync()
        // Three new messages in one batch: one receipt, not three.
        assertEquals(1, rig.bobPosts().size)

        rig.ana1.processor.sync()
        for (i in 1..3) assertEquals("delivered", rig.ana1.repo.message("m-$i")?.status)

        // A second batch is a second receipt; an empty one is none.
        rig.ana1.outbox.enqueue(ContentEnvelope.Text("m-4", NOW + 4, "four"), "acc-bob")
        rig.ana1.outbox.flush()
        rig.bob1.processor.sync()
        rig.bob1.processor.sync()
        assertEquals(2, rig.bobPosts().size)
        rig.assertLockDiscipline()
    }

    @Test
    fun noReceiptForOwnMessagesOrPlaceholders() = runTest {
        val rig = joined()
        rig.bob1.receipts.attach(rig.bob1.processor)
        // Bob sends; ana's own other device echoes nothing to bob. Only a message from someone else counts.
        rig.bob1.outbox.enqueue(ContentEnvelope.Text("b-1", NOW, "from bob"), "acc-ana")
        rig.bob1.outbox.flush()
        rig.bob1.processor.sync()
        assertEquals(1, rig.bobPosts().size)
    }

    @Test
    fun lostDeliveredReceiptDoesNotBreakTheDrain() = runTest {
        val rig = joined()
        rig.bob1.receipts.attach(rig.bob1.processor)
        rig.ana1.outbox.enqueue(ContentEnvelope.Text("m-1", NOW, "one"), "acc-bob")
        rig.ana1.outbox.flush()
        rig.server.failNextMessages = 1
        rig.bob1.processor.sync()
        assertEquals("delivered", rig.bob1.repo.message("m-1")?.status)
        assertEquals(0, rig.bobPosts().size)
    }

    @Test
    fun readReceiptIsQueuedAndTheDuplicateNeverLowersAStatus() = runTest {
        val rig = joined()
        rig.ana1.outbox.enqueue(ContentEnvelope.Text("m-1", NOW, "one"), "acc-bob")
        rig.ana1.outbox.flush()
        rig.bob1.processor.sync()

        rig.bob1.receipts.read("acc-ana", listOf("m-1"))
        assertEquals(1, rig.bob1.repo.dueOutbox(Long.MAX_VALUE).size)
        rig.bob1.outbox.flush()
        rig.ana1.processor.sync()
        assertEquals("read", rig.ana1.repo.message("m-1")?.status)

        // A late `delivered` for the same message changes nothing.
        rig.bob1.outbox.sendControl("acc-ana") { id, at -> ContentEnvelope.Receipt(id, at, ReceiptKind.Delivered, listOf("m-1")) }
        rig.ana1.processor.sync()
        assertEquals("read", rig.ana1.repo.message("m-1")?.status)
    }

    // The Read receipts switch (owner decision 2026-10-29)

    @Test
    fun switchOffSendsNoReadReceiptButDeliveredStillGoOut() = runTest {
        val rig = joined()
        var enabled = false
        val receipts = Receipts(rig.bob1.outbox) { enabled }
        receipts.attach(rig.bob1.processor)
        rig.ana1.outbox.enqueue(ContentEnvelope.Text("m-1", NOW, "one"), "acc-bob")
        rig.ana1.outbox.flush()
        rig.bob1.processor.sync()
        assertEquals(1, rig.bobPosts().size, "delivered still sent")

        receipts.read("acc-ana", listOf("m-1"))
        assertEquals(0, rig.bob1.repo.dueOutbox(Long.MAX_VALUE).size)
        rig.bob1.outbox.flush()
        assertEquals(1, rig.bobPosts().size)

        // Switching back on is not retroactive: m-1 stays unreported; new reads are sent.
        enabled = true
        rig.bob1.outbox.flush()
        assertEquals(1, rig.bobPosts().size)
        receipts.read("acc-ana", listOf("m-2"))
        rig.bob1.outbox.flush()
        assertEquals(2, rig.bobPosts().size)
    }

    @Test
    fun queuedReadReceiptIsDroppedWhenTheSwitchIsOffAtSendTime() = runTest {
        val rig = joined()
        rig.ana1.outbox.enqueue(ContentEnvelope.Text("m-1", NOW, "one"), "acc-bob")
        rig.ana1.outbox.flush()
        rig.bob1.processor.sync()
        rig.bob1.receipts.read("acc-ana", listOf("m-1"))
        assertEquals(1, rig.bob1.repo.dueOutbox(Long.MAX_VALUE).size)

        rig.bob1.readReceiptsSwitch = { false }
        rig.bob1.outbox.flush()
        assertEquals(0, rig.bobPosts().size)
        assertEquals(0, rig.bob1.repo.dueOutbox(Long.MAX_VALUE).size, "dropped, not held")

        // Back on: still nothing goes out for it.
        rig.bob1.readReceiptsSwitch = { true }
        rig.bob1.outbox.flush()
        assertEquals(0, rig.bobPosts().size)
    }

    @Test
    fun droppedReadReceiptDoesNotBlockTheChatQueueAndDeliveredAndMessagesStillGo() = runTest {
        val rig = joined()
        rig.ana1.outbox.enqueue(ContentEnvelope.Text("m-1", NOW, "one"), "acc-bob")
        rig.ana1.outbox.flush()
        rig.bob1.processor.sync()
        rig.bob1.receipts.read("acc-ana", listOf("m-1"))
        rig.bob1.outbox.enqueue(ContentEnvelope.Text("b-1", NOW, "reply"), "acc-ana")
        rig.bob1.outbox.enqueueControl("acc-ana") { id, at -> ContentEnvelope.Receipt(id, at, ReceiptKind.Delivered, listOf("m-1")) }

        rig.bob1.readReceiptsSwitch = { false }
        rig.bob1.outbox.flush()
        assertEquals(2, rig.bobPosts().size, "the reply and the delivered receipt are sent")
        assertEquals(0, rig.bob1.repo.dueOutbox(Long.MAX_VALUE).size)
        rig.ana1.processor.sync()
        assertEquals("delivered", rig.ana1.repo.message("m-1")?.status)
    }

    @Test
    fun anotherDeviceSwitchingItOffThroughSyncStopsQueuedReadReceipts() = runTest {
        val rig = joined()
        val bob = rig.bob1
        bob.readReceiptsSwitch = { bob.repo.get("setting.readReceipts")?.decodeToString() != "false" }
        rig.ana1.outbox.enqueue(ContentEnvelope.Text("m-1", NOW, "one"), "acc-bob")
        rig.ana1.outbox.flush()
        bob.processor.sync()
        bob.receipts.read("acc-ana", listOf("m-1"))
        assertEquals(1, bob.repo.dueOutbox(Long.MAX_VALUE).size)

        // bob2 turned the switch off; its snapshot arrives here.
        val snapshot = LinkSnapshot(1, emptyList(), emptyList(), emptyList(), emptyList(), mapOf("setting.readReceipts" to "false".encodeToByteArray()))
        bob.deviceSync.import(snapshot.encode())
        bob.outbox.flush()
        assertEquals(0, rig.bobPosts().size)
        assertEquals(0, bob.repo.dueOutbox(Long.MAX_VALUE).size)
    }

    @Test
    fun displayedStatusHidesReadWhileTheSwitchIsOff() = runTest {
        val rig = joined()
        rig.ana1.outbox.enqueue(ContentEnvelope.Text("m-1", NOW, "one"), "acc-bob")
        rig.ana1.outbox.flush()
        rig.bob1.processor.sync()
        rig.bob1.receipts.read("acc-ana", listOf("m-1"))
        rig.bob1.outbox.flush()
        rig.ana1.processor.sync()
        // Stored as read either way, so turning the switch on shows it.
        val stored = rig.ana1.repo.message("m-1")!!.status
        assertEquals("read", stored)
        assertEquals("delivered", Receipts.displayStatus(stored, showRead = false))
        assertEquals("read", Receipts.displayStatus(stored, showRead = true))
        assertEquals("sent", Receipts.displayStatus("sent", showRead = false))
        assertEquals("sending", Receipts.displayStatus("sending", showRead = false))
    }
}
