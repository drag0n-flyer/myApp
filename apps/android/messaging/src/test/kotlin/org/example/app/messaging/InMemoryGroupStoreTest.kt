package org.example.app.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.app.messaging.ports.ChatRecord
import org.example.app.messaging.ports.GroupMemberRecord
import org.example.app.messaging.ports.MessageRecord
import org.example.app.messaging.testing.InMemoryRepository

/** The in-memory repository follows the same group rules as the Room one (see `RepositoryContract`). */
class InMemoryGroupStoreTest {
    private val repo = InMemoryRepository().also {
        it.upsertChat(ChatRecord("chat1", false, 0, false, 1))
        it.upsertGroup("g:c1", "c1", "Book club", 5)
    }
    private fun m(id: String, role: String = "member") = GroupMemberRecord(id, role)

    @Test fun membersFollowTheHighestVersionOnly() {
        assertEquals(emptyList(), repo.setGroupMembers("g:c1", listOf(m("b"), m("a", "owner")), 0))
        assertEquals(listOf(m("a", "owner"), m("b")), repo.groupMembers("g:c1"))
        assertNull(repo.setGroupMembers("g:c1", listOf(m("z")), 0))
        assertEquals(listOf(m("a", "owner"), m("b")), repo.setGroupMembers("g:c1", listOf(m("c")), 1))
        assertEquals(1L, repo.chat("g:c1")!!.membersVersion)
        assertFailsWith<IllegalArgumentException> { repo.setGroupMembers("g:c1", listOf(m("a", "root")), 2) }
        assertFailsWith<IllegalArgumentException> { repo.setGroupMembers("g:c1", (1..101).map { m("m$it") }, 2) }
        assertNull(repo.setGroupMembers("chat1", listOf(m("a")), 5))
        assertEquals(listOf(m("c")), repo.groupMembers("g:c1"))
    }

    @Test fun infoAndStateAreValidated() {
        assertTrue(repo.setGroupInfo("g:c1", "New", byteArrayOf(-1, -40, 1, 2), 2))
        assertFalse(repo.setGroupInfo("g:c1", "Again", null, 2))
        assertFailsWith<IllegalArgumentException> { repo.setGroupInfo("g:c1", "", null, 3) }
        assertFailsWith<IllegalArgumentException> { repo.setGroupInfo("g:c1", "Ok", byteArrayOf(1, 2, 3), 3) }
        assertEquals("New", repo.chat("g:c1")!!.title)
        repo.setMyState("g:c1", "removed")
        assertEquals("removed", repo.chat("g:c1")!!.myState)
        assertFailsWith<IllegalArgumentException> { repo.setMyState("g:c1", "gone") }
        assertFailsWith<IllegalArgumentException> { repo.upsertGroup("chat1", "c9", "X", 1) }
    }

    @Test fun upsertGroupRefusesABadTitleOrConversationId() {
        assertFailsWith<IllegalArgumentException> { repo.upsertGroup("g:x", "x", " padded ", 1) }
        assertFailsWith<IllegalArgumentException> { repo.upsertGroup("g:x/y", "x/y", "Ok", 1) }
        assertNull(repo.chat("g:x"))
    }

    @Test fun receiptsAreUniquePerMemberAndKind() {
        repo.insertMessage(MessageRecord("m1", "g:c1", null, "me", null, 1, 1, byteArrayOf(1), "sent"))
        assertTrue(repo.addReceipt("m1", "bob", "delivered", 100))
        assertFalse(repo.addReceipt("m1", "bob", "delivered", 200))
        assertTrue(repo.addReceipt("m1", "bob", "read", 300))
        assertEquals(2, repo.receipts("m1").size)
        assertEquals(100L, repo.receipts("m1").first { it.kind == "delivered" }.at)
        assertFalse(repo.addReceipt("missing", "bob", "read", 1))
        assertFailsWith<IllegalArgumentException> { repo.addReceipt("m1", "bob", "seen", 1) }
    }

    @Test fun deletingTheChatRemovesMembersAndReceipts() {
        repo.setGroupMembers("g:c1", listOf(m("a", "owner")), 1)
        repo.insertMessage(MessageRecord("m1", "g:c1", null, "me", null, 1, 1, byteArrayOf(1), "sent"))
        repo.addReceipt("m1", "a", "read", 5)
        repo.deleteChat("g:c1")
        assertTrue(repo.groupMembers("g:c1").isEmpty())
        assertTrue(repo.receipts("m1").isEmpty())
    }
}
