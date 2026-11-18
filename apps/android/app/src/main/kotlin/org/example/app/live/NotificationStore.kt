package org.example.app.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.example.app.core.chats.Chat
import org.example.app.core.chats.DraftKeeper
import org.example.app.messaging.content.TextLimit
import org.example.app.push.Deadline
import org.example.app.push.NotificationActionSink

/** What the notification buttons need from the signed-in session. The app adapts its live store to this. */
interface NotificationStore {
    val meId: String
    fun chat(chatId: String): Chat?
    /** Account IDs this device has blocked; a chat's ID is its contact's account ID. */
    fun blocked(): Set<String>
    suspend fun markRead(chatId: String)
    /** The new message's id once it is in the outbox; null when nothing was queued. */
    suspend fun sendReturningId(chatId: String, text: String, waitMs: Long): String?
    /** Returns when the outbox has tried to send what is queued. */
    suspend fun settle()
}

/**
 * The notification buttons over the one live session the app holds (`openSession` is cached and opens once), so a
 * button pressed in a cold-started process cannot race the open app.
 */
class LiveNotificationActions(
    private val store: suspend () -> NotificationStore?,
    private val drafts: (accountId: String) -> DraftKeeper,
    /** The longest to wait for the new message's id, and for the outbox's first send attempt. Both also end at the deadline. */
    private val idWaitMs: Long = 4_000,
    private val settleMs: Long = 3_000,
    /** Waits for [block] at most the given milliseconds; a seam for tests. */
    private val bounded: suspend (Long, suspend () -> Unit) -> Unit = { ms, block -> withTimeoutOrNull(ms) { block() } },
) : NotificationActionSink {
    override suspend fun markRead(chatId: String): Boolean {
        val s = store() ?: return false
        if (s.chat(chatId) == null) return false // an ID from an intent is checked against the store first
        s.markRead(chatId) // also sends the read receipt when the setting allows
        return true
    }

    /**
     * True only when the message is in the outbox. Refused (false, nothing queued) for an unknown chat, a message
     * request, a blocked contact, or a text over the limit, which the send path would cut short.
     */
    override suspend fun reply(chatId: String, text: String, deadline: Deadline): Boolean {
        val s = store() ?: return false
        val chat = s.chat(chatId) ?: return false
        if (chat.isRequest || chatId in s.blocked()) return false
        if (TextLimit.truncate(text) != text) return false
        s.sendReturningId(chatId, text, idWaitMs) ?: return false
        // Queued. The outbox flushes in the session's own scope, not inside this broadcast, and the process may be cached
        // away once the broadcast ends. Hold it open (bounded) for the first send attempt. Nothing below can make a
        // queued message "not sent": the caller would save a draft and the person would send it twice.
        try {
            bounded(settleMs) { s.settle() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The message stays queued; the outbox retries it.
        }
        return true
    }

    override suspend fun saveDraft(chatId: String, text: String) {
        val s = store() ?: return
        saveReplyDraft(drafts(s.meId), chatId, text)
    }
}
