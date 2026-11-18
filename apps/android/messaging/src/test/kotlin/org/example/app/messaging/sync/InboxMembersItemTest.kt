package org.example.app.messaging.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.MembersEvent

/** A `members` inbox item is an event, never a message, a placeholder or a raw row. */
class InboxMembersItemTest {
    @Test
    fun aMembersItemIsHandedOverAsAnEventAndLeavesNoRowBehind() = runTest {
        val rig = Rig().start()
        val record = rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        val events = mutableListOf<MembersEvent>()
        rig.bob1.processor.onMembers = { events += it }
        val before = rig.bob1.repo.messages("acc-ana", null, 50).size
        rig.server.deliverMembers("acc-bob/bob1", record.serverId, 7)
        assertEquals(1, rig.bob1.processor.sync())
        assertEquals(listOf(record.serverId to 7L), events.map { it.conversationId to it.membersVersion })
        assertEquals(before, rig.bob1.repo.messages("acc-ana", null, 50).size, "no placeholder or message")
        assertTrue(rig.bob1.repo.rawItems(10).isEmpty())
        assertTrue(rig.server.pending("acc-bob/bob1").isEmpty(), "acknowledged")
        assertEquals(1, rig.bob1.repo.chats().size)
        rig.assertLockDiscipline()
    }

    @Test
    fun withNoListenerAMembersItemIsDroppedForAConversationThisDeviceDoesNotKnow() = runTest {
        val rig = Rig().start()
        rig.server.deliverMembers("acc-bob/bob1", "unknown-conversation", 3)
        assertEquals(1, rig.bob1.processor.sync())
        assertTrue(rig.bob1.repo.chats().isEmpty())
        assertTrue(rig.bob1.repo.rawItems(10).isEmpty())
        assertTrue(rig.server.pending("acc-bob/bob1").isEmpty())
    }

    @Test
    fun aListenerThatFailsDoesNotLoseTheOtherItemsOfThePage() = runTest {
        val rig = Rig().start()
        val record = rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        rig.bob1.processor.onMembers = { error("boom") }
        rig.server.deliverMembers("acc-bob/bob1", record.serverId, 2)
        rig.ana1.send(record, org.example.app.messaging.content.ContentEnvelope.Text("m-1", NOW, "after"))
        rig.bob1.processor.sync()
        assertEquals(listOf("m-1"), rig.bob1.repo.messages("acc-ana", null, 50).map { it.id }.filter { it == "m-1" })
    }
}
