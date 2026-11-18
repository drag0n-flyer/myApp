package org.example.app.ui.search

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.MessageHit

/** A message hit with the name of the chat it is in. */
data class MessageResult(val hit: MessageHit, val chatName: String, val colorKey: String? = null)

sealed interface SearchRow {
    data class Contact(val contact: org.example.app.core.chats.Contact) : SearchRow
    data class Message(val result: MessageResult) : SearchRow
}

data class SearchState(
    val query: String = "",
    val contacts: List<org.example.app.core.chats.Contact> = emptyList(),
    val messages: List<MessageResult> = emptyList(),
    /** True once a non-blank query has been answered: tells "No results" from "nothing typed yet". */
    val searched: Boolean = false,
) {
    /** Contacts first, then message hits. */
    val rows: List<SearchRow> get() = contacts.map { SearchRow.Contact(it) } + messages.map { SearchRow.Message(it) }
}

/**
 * Search from the chat list: contacts whose name or username contains the query (any case), then message hits from
 * `ChatStore.searchMessages`. A blank query (or just "@") gives nothing.
 */
class SearchViewModel(
    private val store: ChatStore,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val messageLimit: Int = 50,
    /** Typing waits this long before the database is asked. */
    private val debounceMs: Long = 300,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    fun setQuery(text: String) {
        job?.cancel()
        val query = text.trim().trimStart('@').trim()
        if (query.isEmpty()) {
            _state.value = SearchState(query = text)
            return
        }
        _state.value = _state.value.copy(query = text)
        job = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val contacts = store.contacts.value.filter {
                it.displayName.contains(query, ignoreCase = true) || it.username.contains(query, ignoreCase = true)
            }
            val messages = runCatching { store.searchMessages(query, messageLimit) }.getOrDefault(emptyList()).map { hit ->
                MessageResult(hit, store.chat(hit.chatId)?.let { it.title ?: it.contactId?.let { id -> store.contact(id)?.displayName } } ?: hit.chatId, store.chat(hit.chatId)?.contactId)
            }
            // A newer query cancelled this one while the database call was running: its results are stale.
            ensureActive()
            _state.update { if (it.query == text) SearchState(text, contacts, messages, searched = true) else it }
        }
    }

    override fun onCleared() {
        scope.cancel()
    }
}
