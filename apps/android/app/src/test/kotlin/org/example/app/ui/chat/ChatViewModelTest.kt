package org.example.app.ui.chat

import kotlin.test.assertEquals
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.ui.msg
import org.example.app.ui.storeWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private class CountingStore(private val inner: SampleChatStore) : ChatStore by inner {
        var chatReads = 0
        override fun chat(id: String) = inner.chat(id).also { chatReads++ }
    }

    @Test
    fun aChangeToAnotherChatDoesNotRecomputeThisChatsState() {
        val inner = storeWith(listOf(msg("1", "riya", MessageContent.Text("hi"))))
        val store = CountingStore(inner)
        val vm = ChatViewModel(store, "chat-riya", NetworkType.Wifi, AutoDownloadSettings())
        val reads = store.chatReads
        // Another contact's message changes the chat list but not this chat.
        val stranger = inner.contacts.value.first { it.id != "riya" }
        inner.receive(MessageContent.Text("elsewhere"), stranger)
        assertEquals(reads, store.chatReads)
        // A message in this chat does update the state.
        inner.receive(MessageContent.Text("here"), inner.contact("riya")!!)
        assertEquals(2, vm.state.value.messages.size)
    }
}
