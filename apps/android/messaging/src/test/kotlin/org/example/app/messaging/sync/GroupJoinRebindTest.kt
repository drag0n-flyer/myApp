package org.example.app.messaging.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.example.app.messaging.ports.ChatRecord
import org.example.app.messaging.ports.ConversationRecord

/** A group Welcome never takes over the conversation row of a one-to-one chat, active or not. */
class GroupJoinRebindTest {
    private fun refused(active: Boolean) = runTest {
        val w = GroupWorld.trio()
        val bob = w.device("bob1")
        // A one-to-one chat with ana whose server conversation has the ID the new group will get.
        bob.repo.upsertChat(ChatRecord("acc-ana", false, 0, false, 1))
        val old = ConversationRecord("conv-0001", "acc-ana", byteArrayOf(9, 9, 9), "e2ee", "e2ee", false, 4, active, 1)
        bob.repo.upsertConversation(old)
        val made = w.device("ana1").groups.create(listOf("acc-bob", "acc-cat"), "Book club", null)
        w.device("ana1").outbox.flush()
        bob.processor.sync()
        val row = assertNotNull(bob.repo.conversation("conv-0001"))
        assertEquals("acc-ana", row.chatId, "still the one-to-one chat's row")
        assertContentEquals(byteArrayOf(9, 9, 9), row.groupId)
        assertEquals(active, row.active)
        assertEquals(4L, row.epoch)
        assertNull(bob.repo.chat(made.chatId)?.takeIf { bob.repo.conversations(it.id).isNotEmpty() }, "no group conversation was made")
        assertTrue(bob.repo.rawItems(10).isEmpty(), "the Welcome is not retried for ever")
    }

    @Test
    fun anActiveOneToOneRowIsNotRebound() = refused(active = true)

    @Test
    fun anInactiveOneToOneRowIsNotRebound() = refused(active = false)
}
