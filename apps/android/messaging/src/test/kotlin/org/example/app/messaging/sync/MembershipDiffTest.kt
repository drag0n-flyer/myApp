package org.example.app.messaging.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.app.contracts.MemberKey
import org.example.app.messaging.ports.ChatRecord
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.ports.MemberRecord
import org.example.app.messaging.testing.InMemoryRepository

class MembershipDiffTest {
    private val me = LocalDevice("acc-ana", "ana1")
    private fun key(identity: String) = MemberKey(identity, "k-$identity".encodeToByteArray())
    private fun stored(vararg identities: String) = identities.map { MemberRecord(it, "k-$it".encodeToByteArray()) }

    @Test
    fun noStoredRowsMeansNoLinesAndNoNotice() {
        // The commit or Welcome that creates the conversation: members are stored, nothing is reported.
        val repo = InMemoryRepository()
        val record = ConversationRecord("conv-1", "acc-bob", "g".encodeToByteArray(), "e2ee", "e2ee", false, 1, true, NOW)
        repo.upsertChat(ChatRecord("acc-bob", false, 0, false, NOW))
        repo.upsertConversation(record)
        val change = Membership.write(me, listOf(key("acc-ana/ana1"), key("acc-ana/ana2"), key("acc-bob/bob1")), record, repo, NOW)
        assertEquals(MembershipChange(), change)
        assertEquals(3, repo.members("conv-1").size)
        assertEquals(emptyList(), repo.messages("acc-bob", null, 10))
    }

    @Test
    fun writeAddsLinesInOrderAndASafetyLineOnlyWhenTheNumberChanged() {
        val repo = InMemoryRepository()
        val record = ConversationRecord("conv-1", "acc-bob", "g".encodeToByteArray(), "e2ee", "e2ee", false, 1, true, NOW)
        repo.upsertChat(ChatRecord("acc-bob", false, 0, false, NOW))
        repo.upsertConversation(record)
        Membership.write(me, listOf(key("acc-ana/ana1"), key("acc-bob/bob1")), record, repo, NOW)
        repo.setEpoch("conv-1", 2)
        val change = Membership.write(me, listOf(key("acc-ana/ana1"), key("acc-ana/ana2"), key("acc-bob/bob1"), key("acc-bob/bob2")), record, repo, NOW)
        assertEquals(listOf("ana2"), change.ownDevicesAdded)
        val lines = repo.messages("acc-bob", null, 10)
        assertEquals(
            listOf("sys-conv-1-2-add-acc-ana/ana2", "sys-conv-1-2-add-acc-bob/bob2", "sys-conv-1-2-safety"),
            lines.map { it.id },
        )
        assertEquals(listOf(NOW, NOW + 1, NOW + 2), lines.map { it.sentAt })
        assertEquals("""{"deviceAdded":{}}""", lines[0].envelope.decodeToString())
        // The same members again: nothing new.
        Membership.write(me, listOf(key("acc-ana/ana1"), key("acc-ana/ana2"), key("acc-bob/bob1"), key("acc-bob/bob2")), record, repo, NOW)
        assertEquals(3, repo.messages("acc-bob", null, 10).size)
    }

    @Test
    fun addedRemovedAndOwnDevices() {
        val change = Membership.diff(
            me, stored("acc-ana/ana1", "acc-bob/bob1", "acc-bob/bob2"),
            listOf(key("acc-ana/ana1"), key("acc-ana/ana2"), key("acc-bob/bob1"), key("acc-bob/bob3")),
        )
        assertEquals(listOf("acc-ana/ana2", "acc-bob/bob3"), change.added)
        assertEquals(listOf("acc-bob/bob2"), change.removed)
        assertEquals(listOf("ana2"), change.ownDevicesAdded)
    }

    @Test
    fun thisDeviceIsNeverReported() {
        val change = Membership.diff(me, stored("acc-bob/bob1"), listOf(key("acc-ana/ana1"), key("acc-bob/bob1")))
        assertEquals(MembershipChange(), change)
    }

    @Test
    fun lineEnvelopesMatchTheIphone() {
        assertEquals("""{"deviceAdded":{"name":"acc-bob"}}""", Membership.lineEnvelope("deviceAdded", "acc-bob").decodeToString())
        assertEquals("""{"deviceRemoved":{}}""", Membership.lineEnvelope("deviceRemoved", null).decodeToString())
        assertEquals("""{"safetyNumberChanged":{}}""", Membership.lineEnvelope("safetyNumberChanged", null).decodeToString())
    }
}
