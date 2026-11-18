package org.example.app.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.example.app.core.account.ConnectionStatus
import org.example.app.core.calls.DataModePolicy
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.ChatFolder
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.MessagePreview
import org.example.app.core.settings.UserSettings
import org.example.app.core.settings.ServerPolicy

data class FolderChip(val folder: ChatFolder, val unreadCount: Int)

data class ChatRowModel(
    val chatId: String,
    val name: String,
    val preview: MessagePreview?,
    val time: Instant?,
    val unreadCount: Int,
    /** The other person's account ID, which picks the colour of the letter tile. */
    val colorKey: String? = null,
)

data class ChatListState(
    val folders: List<FolderChip>,
    val selectedFolderId: String,
    /** Number of message requests; they never count towards Unread. */
    val requestCount: Int,
    val rows: List<ChatRowModel>,
    val serverHost: String,
    val modeKey: String,
    val status: ConnectionStatus = ConnectionStatus.Connected,
)

class ChatListViewModel(
    private val store: ChatStore,
    private val server: ServerPolicy,
    private val settings: StateFlow<UserSettings>,
    private val network: NetworkType,
    /** The live connection (`AppGraph.connectionStatus`); sample data and tests are connected. */
    private val status: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.Connected),
) : ViewModel() {
    private val selected = MutableStateFlow(ChatFolder.All.id)

    val state: StateFlow<ChatListState> =
        combine(store.chats, store.requests, store.folders, selected, settings) { _, requests, folders, selectedId, user ->
            val folder = folders.firstOrNull { it.id == selectedId } ?: ChatFolder.All
            ChatListState(
                folders = folders.map { FolderChip(it, if (it.kind == ChatFolder.Kind.Unread) store.unreadChatCount() else 0) },
                selectedFolderId = folder.id,
                requestCount = requests.size,
                rows = store.chats(folder).map { chat ->
                    val last = chat.lastMessage
                    ChatRowModel(chat.id, store.contact(chat.contactId)?.displayName ?: chat.contactId, last?.preview, last?.sentAt, chat.unreadCount, chat.contactId)
                },
                serverHost = server.host,
                modeKey = DataModePolicy.stripLabelKey(server, user.call, network),
            )
        }.combine(status) { state, connection -> state.copy(status = connection) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, initial().copy(status = status.value))

    private fun initial() = ChatListState(emptyList(), ChatFolder.All.id, 0, emptyList(), server.host, DataModePolicy.stripLabelKey(server, settings.value.call, network))

    fun select(folderId: String) { selected.value = folderId }

    fun markAllRead() {
        store.chats.value.filter { it.unreadCount > 0 }.forEach { store.markRead(it.id) }
    }
}
