package org.example.app.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Chat info's Search button asks the chat underneath it to open its search field. The two screens do not know each
 * other, so the request waits here until the chat screen is back on top and takes it.
 */
object ChatSearchRequests {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    fun request(chatId: String) {
        _pending.value = chatId
    }

    /** True when [chatId] had a request waiting; the request is then cleared. */
    fun take(chatId: String): Boolean {
        if (_pending.value != chatId) return false
        _pending.value = null
        return true
    }
}
