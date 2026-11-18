package org.example.app.core.chats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SampleGroupCreateTest {
    private val store = SampleChatStore()

    @Test
    fun aSampleGroupOfTwoContactsAppearsInTheChatListWithItsName() = runBlocking {
        val two = store.contacts.value.take(2).map { it.id }
        val made = store.createGroup(two, "Book club", null)
        assertEquals(emptyList(), made.notAdded)
        val chat = store.chats.value.single { it.id == made.chatId }
        assertEquals("Book club", chat.title)
        assertNull(chat.contactId)
        assertTrue(chat.isGroup)
        assertEquals(made.chatId, store.chat(made.chatId)?.id)
    }

    @Test
    fun fewerThanTwoPeopleOrABadNameIsRefused() {
        val one = store.contacts.value.take(1).map { it.id }
        assertFailsWith<GroupCreateFailed> { runBlocking { store.createGroup(one, "Too few", null) } }
        val two = store.contacts.value.take(2).map { it.id }
        assertFailsWith<GroupCreateFailed> { runBlocking { store.createGroup(two, "  ", null) } }
    }
}
