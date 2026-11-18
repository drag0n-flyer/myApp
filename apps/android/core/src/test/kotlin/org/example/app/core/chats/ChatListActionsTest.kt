package org.example.app.core.chats

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatListActionsTest {
    private val start = Instant.parse("2026-10-28T12:00:00Z")
    private var now = start
    private fun store() = SampleChatStore(SampleData.make(start), clock = { now })
    private fun tick() { now = now.plusSeconds(10) }
    private fun ids(s: SampleChatStore) = s.chats.value.map { it.id }

    @Test fun pinnedChatMovesFirstAndNewestPinWins() {
        val s = store()
        val before = ids(s)
        val a = before[3]
        val b = before[5]
        tick(); assertTrue(s.setPinned(a, true))
        assertEquals(a, ids(s).first())
        assertEquals(now, s.chat(a)!!.pinnedAt)
        tick(); assertTrue(s.setPinned(b, true))
        assertEquals(listOf(b, a), ids(s).take(2))
        assertTrue(s.setPinned(a, false))
        assertNull(s.chat(a)!!.pinnedAt)
        assertEquals(b, ids(s).first())
        assertFalse(a in ids(s).take(1))
    }

    @Test fun theSixthPinIsRefusedAndNothingChanges() {
        val s = store()
        val all = ids(s)
        for (i in 0 until ChatListOrder.MAX_PINS) { tick(); assertTrue(s.setPinned(all[i], true)) }
        tick()
        assertFalse(s.setPinned(all[5], true))
        assertNull(s.chat(all[5])!!.pinnedAt)
        assertEquals(ChatListOrder.MAX_PINS, s.chats.value.count { it.pinnedAt != null })
        // Pinning one that is already pinned is fine at the limit, and unpinning frees a place.
        assertTrue(s.setPinned(all[0], true))
        assertTrue(s.setPinned(all[1], false))
        assertTrue(s.setPinned(all[5], true))
    }

    @Test fun muteIsKeptAndListed() {
        val s = store()
        val id = ids(s)[2]
        s.setMuted(id, true)
        assertTrue(s.chat(id)!!.muted)
        assertEquals(setOf(id), s.mutedChatIds.value)
        s.setMuted(id, false)
        assertFalse(s.chat(id)!!.muted)
        assertTrue(s.mutedChatIds.value.isEmpty())
    }

    @Test fun archivedChatLeavesTheListsButStaysInTheStore() {
        val s = store()
        val id = ids(s)[0]
        val unreadBefore = s.unreadChatCount()
        val unreadHere = s.chat(id)!!.unreadCount
        s.setArchived(id, true)
        assertTrue(s.chat(id)!!.archived)
        assertTrue(s.chats.value.any { it.id == id && it.archived })
        assertTrue(s.chats(ChatFolder.All).none { it.id == id })
        assertTrue(s.chats(ChatFolder.Unread).none { it.id == id })
        assertEquals(unreadBefore - if (unreadHere > 0) 1 else 0, s.unreadChatCount())
        s.setArchived(id, false)
        assertTrue(s.chats(ChatFolder.All).any { it.id == id })
    }

    @Test fun anIncomingMessageNeverChangesArchivedOrMutedButIsStoredAndCounted() {
        val s = store()
        val chat = s.chat(ids(s)[0])!!
        val from = s.contact(chat.contactId!!)!!
        s.setArchived(chat.id, true)
        s.setMuted(chat.id, true)
        tick()
        s.receive(MessageContent.Text("still arrives"), from)
        val after = s.chat(chat.id)!!
        assertTrue(after.archived)
        assertTrue(after.muted)
        assertEquals(chat.unreadCount + 1, after.unreadCount)
        assertEquals("still arrives", (after.messages.last().content as MessageContent.Text).text)
    }

    @Test fun draftIsSavedPerChatAndBlankRemovesIt() {
        val s = store()
        val (a, b) = ids(s).let { it[0] to it[1] }
        s.setDraft(a, "half a thought")
        assertEquals("half a thought", s.chat(a)!!.draft)
        assertNull(s.chat(b)!!.draft)
        s.setDraft(a, "   ")
        assertNull(s.chat(a)!!.draft)
        s.setDraft(a, "again")
        s.setDraft(a, null)
        assertNull(s.chat(a)!!.draft)
    }

    @Test fun aDraftSurvivesOtherChangesToTheChat() {
        val s = store()
        val id = ids(s)[0]
        s.setDraft(id, "keep me")
        s.markRead(id)
        s.setPinned(id, true)
        s.setMuted(id, true)
        assertEquals("keep me", s.chat(id)!!.draft)
    }

    @Test fun archivingUnpinsAndUnarchivingDoesNotRepin() {
        val s = store()
        val id = ids(s)[2]
        tick(); assertTrue(s.setPinned(id, true))
        s.setArchived(id, true)
        assertNull(s.chat(id)!!.pinnedAt)
        s.setArchived(id, false)
        assertNull(s.chat(id)!!.pinnedAt)
    }

    @Test fun anArchivedChatNeverCountsTowardTheFivePins() {
        val s = store()
        val all = ids(s)
        for (i in 0 until ChatListOrder.MAX_PINS) { tick(); assertTrue(s.setPinned(all[i], true)) }
        assertFalse(s.setPinned(all[5], true))
        s.setArchived(all[0], true)
        assertTrue(s.setPinned(all[5], true), "archiving freed a place")
    }

    @Test fun anArchivedChatCannotBePinned() {
        val s = store()
        val id = ids(s)[1]
        s.setArchived(id, true)
        assertFalse(s.setPinned(id, true))
        assertNull(s.chat(id)!!.pinnedAt)
    }
}
