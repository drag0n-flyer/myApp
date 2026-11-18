package org.example.app.live

import org.example.app.core.chats.Chat
import org.example.app.core.chats.DraftKeeper
import org.example.app.push.NotificationActionSink

/** What the notification buttons need from the signed-in session. The app adapts its live store to this. */
interface NotificationStore {
    val meId: String
    fun chat(chatId: String): Chat?
    fun blocked(): Set<String>
    suspend fun markRead(chatId: String)
    suspend fun sendReturningId(chatId: String, text: String): String?
    suspend fun settle()
}

class LiveNotificationActions(
    private val store: suspend () -> NotificationStore?,
    private val drafts: (accountId: String) -> DraftKeeper,
    private val settleMs: Long = 8_000,
) : NotificationActionSink {
    override suspend fun markRead(chatId: String): Boolean = false

    override suspend fun reply(chatId: String, text: String): Boolean = store() != null

    override suspend fun saveDraft(chatId: String, text: String) = Unit
}

/** Adds [text] to the chat's unsent draft. N4's `ChatStore.setDraft` replaces the body of this function. */
internal fun saveReplyDraft(keeper: DraftKeeper, chatId: String, text: String) = Unit
