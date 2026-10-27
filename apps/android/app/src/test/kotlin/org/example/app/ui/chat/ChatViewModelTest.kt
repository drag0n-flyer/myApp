package org.example.app.ui.chat

import kotlin.test.assertEquals
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.core.settings.MediaKind
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
        val vm = ChatViewModel(store, "chat-riya", NetworkType.Wifi) { AutoDownloadSettings() }
        val reads = store.chatReads
        // Another contact's message changes the chat list but not this chat.
        val stranger = inner.contacts.value.first { it.id != "riya" }
        inner.receive(MessageContent.Text("elsewhere"), stranger)
        assertEquals(reads, store.chatReads)
        // A message in this chat does update the state.
        inner.receive(MessageContent.Text("here"), inner.contact("riya")!!)
        assertEquals(2, vm.state.value.messages.size)
    }

    @Test
    fun autoDownloadIsRequestedOncePerMessage() {
        val downloads = mutableListOf<String>()
        val inner = storeWith(listOf(msg("1", "riya", MessageContent.Photo(1000))))
        val store = object : ChatStore by inner {
            override fun download(messageId: String, chatId: String) { downloads += messageId }
        }
        val vm = ChatViewModel(store, "chat-riya", NetworkType.Wifi) { AutoDownloadSettings() }
        vm.requestAutoDownload("1", MediaKind.Photo)
        vm.requestAutoDownload("1", MediaKind.Photo)
        assertEquals(listOf("1"), downloads)
        // A tap still downloads.
        vm.download("1")
        assertEquals(listOf("1", "1"), downloads)
    }

    @Test
    fun autoDownloadRespectsTheNetworkRule() {
        val downloads = mutableListOf<String>()
        val inner = storeWith(listOf(msg("1", "riya", MessageContent.Photo(1000))))
        val store = object : ChatStore by inner {
            override fun download(messageId: String, chatId: String) { downloads += messageId }
        }
        val vm = ChatViewModel(store, "chat-riya", NetworkType.Mobile) { AutoDownloadSettings() }
        vm.requestAutoDownload("1", MediaKind.Photo)
        assertEquals(emptyList(), downloads)
    }
}
