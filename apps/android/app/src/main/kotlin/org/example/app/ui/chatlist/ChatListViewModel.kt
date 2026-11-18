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
import org.example.app.core.chats.Chat
import org.example.app.core.chats.ChatFolder
import org.example.app.core.chats.ChatListOrder
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
    val pinned: Boolean = false,
    val muted: Boolean = false,
    /** Unsent text saved for this chat; the row shows it in place of the last message. */
    val draft: String? = null,
    /** The other person's account ID, which picks the colour of the letter tile. */
    val colorKey: String? = null,
    /** The other person is typing: the row shows "typing…" in place of the preview. */
    val typing: Boolean = false,
    /** In a group, the names of the people typing (null for one whose name is not known); empty in a one-to-one chat. */
    val typers: List<String?> = emptyList(),
    /** In a group, who sent the last message when it is not me: the row shows "Name: text". */
    val previewSender: String? = null,
    /** In a group, the last message is mine: the row shows "You: text". */
    val previewFromMe: Boolean = false,
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
    /** The archive: its chats, and the unread chats among them (shown on the Archived row). */
    val archivedRows: List<ChatRowModel> = emptyList(),
    val archivedUnread: Int = 0,
)

class ChatListViewModel(
    private val store: ChatStore,
    private val server: ServerPolicy,
    private val settings: StateFlow<UserSettings>,
    private val network: NetworkType,
    /** The live connection (`AppGraph.connectionStatus`); sample data and tests are connected. */
    private val status: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.Connected),
    /** Typing signals for the rows; null shows none. */
    signals: org.example.app.core.people.PeopleSignals? = null,
) : ViewModel() {
    private val selected = MutableStateFlow(ChatFolder.All.id)

    val state: StateFlow<ChatListState> =
        combine(store.chats, store.requests, store.folders, selected, settings) { all, requests, folders, selectedId, user ->
            val archived = ChatListOrder.visible(all, archive = true)
            val folder = folders.firstOrNull { it.id == selectedId } ?: ChatFolder.All
            ChatListState(
                folders = folders.map { FolderChip(it, if (it.kind == ChatFolder.Kind.Unread) store.unreadChatCount() else 0) },
                selectedFolderId = folder.id,
                requestCount = requests.size,
                rows = store.chats(folder).map(::rowOf),
                archivedRows = archived.map(::rowOf),
                archivedUnread = archived.count { it.unreadCount > 0 },
                serverHost = server.host,
                modeKey = DataModePolicy.stripLabelKey(server, user.call, network),
            )
        }.combine(status) { state, connection -> state.copy(status = connection) }
            .combine(signals?.views ?: MutableStateFlow<Map<String, org.example.app.core.people.PeerView>>(emptyMap())) { state, views ->
                if (views.values.none { it.typing }) state
                else state.copy(
                    rows = state.rows.map { withTyping(it, views[it.chatId]) },
                    archivedRows = state.archivedRows.map { withTyping(it, views[it.chatId]) },
                )
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, initial().copy(status = status.value))

    private fun withTyping(row: ChatRowModel, view: org.example.app.core.people.PeerView?): ChatRowModel = row.copy(
        typing = view?.typing == true,
        typers = view?.typers.orEmpty().map { store.personName(it)?.takeIf { n -> n.isNotEmpty() } },
    )

    private fun rowOf(chat: Chat): ChatRowModel {
        val last = chat.lastMessage
        // In a group the preview says who wrote it, except for a line of the chat itself (an added or removed member).
        val named = chat.isGroup && last != null && last.content !is org.example.app.core.chats.MessageContent.System && last.senderId != "system"
        val fromMe = named && last!!.senderId == store.me.id
        return ChatRowModel(
            // A group that has no name yet shows an empty name, which the row replaces with a word.
            chat.id, if (chat.isGroup) chat.title.orEmpty() else chat.contactId?.let { store.contact(it)?.displayName } ?: chat.contactId ?: chat.id,
            last?.preview, last?.sentAt, chat.unreadCount,
            colorKey = chat.contactId ?: chat.id,
            pinned = chat.pinnedAt != null, muted = chat.muted, draft = chat.draft,
            previewSender = if (named && !fromMe) store.personName(last!!.senderId)?.takeIf { it.isNotEmpty() } ?: "" else null,
            previewFromMe = fromMe,
        )
    }

    private fun initial() = ChatListState(emptyList(), ChatFolder.All.id, 0, emptyList(), server.host, DataModePolicy.stripLabelKey(server, settings.value.call, network))

    fun select(folderId: String) { selected.value = folderId }

    /** Archived chats are left alone: they keep their unread count. */
    fun markAllRead() {
        store.chats.value.filter { it.unreadCount > 0 && !it.archived }.forEach { store.markRead(it.id) }
    }

    /** False, and nothing changed, when this would be a sixth pinned chat. */
    fun setPinned(chatId: String, pinned: Boolean): Boolean = store.setPinned(chatId, pinned)
    fun setMuted(chatId: String, muted: Boolean) = store.setMuted(chatId, muted)
    fun setArchived(chatId: String, archived: Boolean) = store.setArchived(chatId, archived)
    fun markRead(chatId: String) = store.markRead(chatId)
}
