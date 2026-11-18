package org.example.app.core.chats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.example.app.core.groups.GroupRole

class SampleGroupMembersTest {
    private val store = SampleChatStore()
    private val ids = store.contacts.value.map { it.id }

    private fun group() = runBlocking { store.createGroup(ids.take(2), "Book club", null).chatId }

    private fun failure(block: suspend () -> Unit) = assertFailsWith<GroupChangeFailed> { runBlocking { block() } }.reason

    @Test
    fun addRemoveAndTransferWorkAndLeaveNeedsANewOwner() = runBlocking {
        val chat = group()
        assertEquals(emptyList(), store.addMembers(chat, listOf(ids[2])))
        assertEquals(setOf(store.me.id, ids[0], ids[1], ids[2]), store.sampleMembers(chat).keys)
        store.removeMember(chat, ids[2])
        assertTrue(ids[2] !in store.sampleMembers(chat))
        assertEquals(GroupChangeFailed.Reason.OwnerMustTransfer, failure { store.leaveGroup(chat) })
        store.setRole(chat, ids[0], GroupRole.Owner)
        assertEquals(GroupRole.Owner, store.sampleMembers(chat)[ids[0]])
        assertEquals(GroupRole.Admin, store.sampleMembers(chat)[store.me.id])
        store.leaveGroup(chat)
        assertTrue(store.me.id !in store.sampleMembers(chat))
    }

    @Test
    fun renameChangesTheTitleAndAddsALineAndRulesAreEnforced() = runBlocking {
        val chat = group()
        store.renameGroup(chat, "  Readers ")
        assertEquals("Readers", store.chat(chat)?.title)
        assertEquals(SystemEvent.GroupRenamed(null, "Readers"), (store.chat(chat)!!.messages.last().content as MessageContent.System).event)
        assertEquals(GroupChangeFailed.Reason.Other, failure { store.renameGroup(chat, "   ") })
        assertEquals(GroupChangeFailed.Reason.Forbidden, failure { store.removeMember(chat, store.me.id) })
        assertEquals(GroupChangeFailed.Reason.Forbidden, failure { store.setRole(chat, store.me.id, GroupRole.Admin) })
        assertEquals(GroupChangeFailed.Reason.Other, failure { store.setGroupPhoto(chat, byteArrayOf(1, 2, 3, 4)) })
        assertEquals(GroupChangeFailed.Reason.NotFound, failure { store.renameGroup("nope", "X") })
    }

    @Test
    fun theHundredthPersonFitsAndTheNextOneIsRefused() = runBlocking {
        val chat = group()
        store.addMembers(chat, (1..97).map { "sample-$it" })
        assertEquals(100, store.sampleMembers(chat).size)
        assertEquals(GroupChangeFailed.Reason.Full, failure { store.addMembers(chat, listOf("one-more")) })
    }

    @Test
    fun everyNewLineHasItsKeyAndArguments() {
        fun key(e: SystemEvent) = Message("m", "system", java.time.Instant.EPOCH, MessageContent.System(e), DeliveryStatus.Sent, null, emptyList()).preview
        assertEquals(MessagePreview.Resource("system_group_added", listOf("Ana", "Bo")), key(SystemEvent.GroupAdded("Ana", "Bo")))
        assertEquals(MessagePreview.Resource("system_group_added_by_you", listOf("Bo")), key(SystemEvent.GroupAdded(null, "Bo", byYou = true)))
        assertEquals(MessagePreview.Resource("system_group_removed_plain", listOf("Bo")), key(SystemEvent.GroupRemoved(null, "Bo")))
        assertEquals(MessagePreview.Resource("system_group_left", listOf("Bo")), key(SystemEvent.GroupLeft("Bo")))
        assertEquals(MessagePreview.Resource("system_you_left"), key(SystemEvent.GroupLeft(null)))
        assertEquals(MessagePreview.Resource("system_you_were_removed"), key(SystemEvent.YouWereRemoved))
        assertEquals(MessagePreview.Resource("system_made_admin", listOf("Bo")), key(SystemEvent.MadeAdmin("Bo")))
        assertEquals(MessagePreview.Resource("system_you_no_longer_admin"), key(SystemEvent.NoLongerAdmin(null)))
        assertEquals(MessagePreview.Resource("system_group_renamed", listOf("Ana", "X")), key(SystemEvent.GroupRenamed("Ana", "X")))
        assertEquals(MessagePreview.Resource("system_group_photo", listOf("Ana")), key(SystemEvent.GroupPhotoChanged("Ana")))
    }
}
