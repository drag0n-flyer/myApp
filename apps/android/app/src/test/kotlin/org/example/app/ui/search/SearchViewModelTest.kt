package org.example.app.ui.search

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.example.app.core.chats.Chat
import org.example.app.core.chats.ChatEncryption
import org.example.app.core.chats.Contact
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData

class SearchViewModelTest {
    private fun store(): SampleChatStore {
        val base = SampleData.make(Instant.now())
        val now = Instant.now()
        // A message from Riya's chat that mentions "sam", so a query for "sam" has a contact and a message hit.
        val riya = base.chats.first { it.id == "chat-riya" }
        val withMention = riya.copy(messages = riya.messages + Message("mention", "riya", now, MessageContent.Text("Ask SAM about it"), DeliveryStatus.Read, null, emptyList()))
        return SampleChatStore(base.copy(chats = base.chats.map { if (it.id == riya.id) withMention else it }))
    }

    private fun vm(store: SampleChatStore = store()) = SearchViewModel(store, Dispatchers.Unconfined, debounceMs = 0)

    @Test
    fun aContactMatchesByNameIgnoringCase() {
        val vm = vm()
        vm.setQuery("rIyA")
        val ids = vm.state.value.contacts.map { it.id }
        assertTrue("riya" in ids && "sam" !in ids)
        vm.setQuery("SAM")
        assertEquals(listOf("sam"), vm.state.value.contacts.map { it.id })
    }

    @Test
    fun aContactMatchesByUsernameIgnoringCase() {
        val data = SampleData.make(Instant.now())
        val odd = Contact("zed", "Zed Q", "quokka_fan")
        val vm = SearchViewModel(SampleChatStore(data.copy(contacts = data.contacts + odd)), Dispatchers.Unconfined, debounceMs = 0)
        vm.setQuery("QUOKKA")
        assertEquals(listOf("zed"), vm.state.value.contacts.map { it.id })
        vm.setQuery("@quokka_fan")
        assertEquals(listOf("zed"), vm.state.value.contacts.map { it.id })
    }

    @Test
    fun contactsComeBeforeMessageHits() {
        val vm = vm()
        vm.setQuery("sam")
        val state = vm.state.value
        assertEquals(listOf("sam"), state.contacts.map { it.id })
        assertTrue(state.messages.any { it.hit.messageId == "mention" })
        assertEquals(listOf(SearchRow.Contact::class, SearchRow.Message::class), state.rows.map { it::class }.distinct())
    }

    @Test
    fun aSlowEarlierQueryFinishingLateDoesNotReplaceNewerResults() {
        val inner = store()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slow = object : org.example.app.core.chats.ChatStore by inner {
            override fun searchMessages(query: String, limit: Int): List<org.example.app.core.chats.MessageHit> {
                if (query == "slow") { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                return inner.searchMessages(query, limit)
            }
        }
        val vm = SearchViewModel(slow, Dispatchers.Default, debounceMs = 0)
        vm.setQuery("slow")
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        vm.setQuery("riya")
        val deadline = System.currentTimeMillis() + 5_000
        while (!vm.state.value.searched && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("riya", vm.state.value.query)
        release.countDown()
        Thread.sleep(300)
        assertEquals("riya", vm.state.value.query)
        assertTrue(vm.state.value.contacts.any { it.id == "riya" })
    }

    @Test
    fun typingIsDebounced() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val inner = store()
        val counting = object : org.example.app.core.chats.ChatStore by inner {
            override fun searchMessages(query: String, limit: Int): List<org.example.app.core.chats.MessageHit> { calls.incrementAndGet(); return inner.searchMessages(query, limit) }
        }
        val vm = SearchViewModel(counting, Dispatchers.Default, debounceMs = 150)
        for (q in listOf("r", "ri", "riy", "riya")) { vm.setQuery(q); Thread.sleep(20) }
        val deadline = System.currentTimeMillis() + 5_000
        while (!vm.state.value.searched && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, calls.get())
        assertEquals("riya", vm.state.value.query)
    }

    @Test
    fun aMessageHitNamesItsChat() {
        val vm = vm()
        vm.setQuery("ask sam")
        val hit = vm.state.value.messages.single()
        assertEquals("chat-riya", hit.hit.chatId)
        assertEquals("Riya", hit.chatName)
    }

    @Test
    fun aBlankQueryGivesNoResults() {
        val vm = vm()
        vm.setQuery("riya")
        assertTrue(vm.state.value.rows.isNotEmpty())
        for (blank in listOf("", "   ", "@")) {
            vm.setQuery(blank)
            assertTrue(vm.state.value.contacts.isEmpty() && vm.state.value.messages.isEmpty(), "query: '$blank'")
        }
    }

    @Test
    fun noMatchGivesNoRowsAndAskedFlag() {
        val vm = vm()
        vm.setQuery("zzzzqqq")
        assertTrue(vm.state.value.rows.isEmpty())
        assertTrue(vm.state.value.searched)
    }
}
