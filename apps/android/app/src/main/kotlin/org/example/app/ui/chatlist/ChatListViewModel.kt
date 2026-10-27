package org.example.app.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.example.app.core.calls.DataModePolicy
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.ChatFolder
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.MessagePreview
import org.example.app.core.settings.CallSettings
import org.example.app.core.settings.ServerPolicy

data class FolderChip(val folder: ChatFolder, val unreadCount: Int)

data class ChatRowModel(
    val chatId: String,
    val name: String,
    val preview: MessagePreview?,
    val time: Instant?,
    val unreadCount: Int,
)

data class ChatListState(
    val folders: List<FolderChip>,
    val selectedFolderId: String,
    /** Number of message requests; they never count towards Unread. */
    val requestCount: Int,
    val rows: List<ChatRowModel>,
    val serverHost: String,
    val modeKey: String,
)

class ChatListViewModel(
    private val store: ChatStore,
    private val server: ServerPolicy,
    private val settings: CallSettings,
    private val network: NetworkType,
) : ViewModel() {
    private val selected = MutableStateFlow(ChatFolder.All.id)

    val state: StateFlow<ChatListState> =
        combine(store.chats, store.requests, store.folders, selected) { _, requests, folders, selectedId ->
            val folder = folders.firstOrNull { it.id == selectedId } ?: ChatFolder.All
            ChatListState(
                folders = folders.map { FolderChip(it, if (it.kind == ChatFolder.Kind.Unread) store.unreadChatCount() else 0) },
                selectedFolderId = folder.id,
                requestCount = requests.size,
                rows = store.chats(folder).map { chat ->
                    val last = chat.lastMessage
                    ChatRowModel(chat.id, store.contact(chat.contactId)?.displayName ?: chat.contactId, last?.preview, last?.sentAt, chat.unreadCount)
                },
                serverHost = server.host,
                modeKey = DataModePolicy.stripLabelKey(server, settings, network),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, initial())

    private fun initial() = ChatListState(emptyList(), ChatFolder.All.id, 0, emptyList(), server.host, DataModePolicy.stripLabelKey(server, settings, network))

    fun select(folderId: String) { selected.value = folderId }

    fun markAllRead() {
        store.chats.value.filter { it.unreadCount > 0 }.forEach { store.markRead(it.id) }
    }
}
