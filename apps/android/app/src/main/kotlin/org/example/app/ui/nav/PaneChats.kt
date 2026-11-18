package org.example.app.ui.nav

import android.annotation.SuppressLint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * The view models of the chat open in the two-pane layout: one chat's at a time. Held in a view model so it survives
 * rotation; the store is cleared when another chat opens or the pane closes, so reopening a chat starts a fresh
 * `ChatViewModel` (it marks the chat read and finds the unread line in `init`) and no collector outlives its chat.
 */
class PaneChats : ViewModel() {
    private var store = ViewModelStore()
    private var current: String? = null

    val owner: ViewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore get() = store
    }

    /** How many view models are alive in the pane (for tests; the store's key list is library-internal). */
    @get:SuppressLint("RestrictedApi")
    val alive: Int get() = store.keys().size

    /** The chat now in the pane (null: none); anything kept for another chat is cleared. */
    fun select(chatId: String?) {
        if (chatId == current) return
        store.clear()
        store = ViewModelStore()
        current = chatId
    }

    override fun onCleared() = store.clear()

    companion object {
        /** One per signed-in session, like the call view model. */
        fun key(sessionEnds: Int) = "pane-chats-$sessionEnds"
    }
}
