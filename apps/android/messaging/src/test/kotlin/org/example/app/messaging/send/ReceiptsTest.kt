package org.example.app.messaging.send

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.example.app.messaging.content.ContentEnvelope
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
}
