package org.example.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.Chat
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.settings.AutoDownloadPolicy
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.core.settings.MediaKind
import org.example.app.messaging.content.TextLimit

data class ChatState(
    val chat: Chat?,
    val contactName: String,
    val messages: List<Message>,
    val sendingPaused: Boolean,
)

class ChatViewModel(
    private val store: ChatStore,
    val chatId: String,
    private val network: NetworkType,
    private val autoDownload: AutoDownloadSettings,
) : ViewModel() {
    // Only this chat matters: other chats changing must not recompute anything.
    val state: StateFlow<ChatState> =
        combine(store.chats, store.requests) { chats, requests ->
            chats.firstOrNull { it.id == chatId } ?: requests.firstOrNull { it.id == chatId }
        }.distinctUntilChanged().map { read(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, read(store.chat(chatId)))

    init {
        store.markRead(chatId)
    }

    private fun read(chat: Chat?): ChatState {
        return ChatState(
            chat = chat,
            contactName = chat?.let { store.contact(it.contactId)?.displayName } ?: "",
            messages = chat?.messages.orEmpty(),
            sendingPaused = chat?.modeMismatch == true,
        )
    }

    /** Sends trimmed text, cut to the grapheme limit. Returns false when there was nothing to send. */
    fun send(text: String, replyTo: String? = null): Boolean {
        val clean = TextLimit.truncate(text).trim()
        if (clean.isEmpty() || state.value.sendingPaused) return false
        store.send(MessageContent.Text(clean), chatId, replyTo)
        return true
    }

    fun download(messageId: String) = store.download(messageId, chatId)

    /** Photos and files wait for a tap when the rules say not to download on this network. */
    fun autoDownloads(kind: MediaKind): Boolean = AutoDownloadPolicy.shouldDownload(kind, network, autoDownload)
}
