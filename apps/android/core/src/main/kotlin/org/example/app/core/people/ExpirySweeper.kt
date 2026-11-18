package org.example.app.core.people

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.MessageContent

/**
 * Removes expired messages from [store] and says when the next one expires. The clock is a parameter so a test can
 * drive it. [onRemoved] gets the removed message IDs and, for each chat that lost an incoming message (not one of mine,
 * not a system line), how many unread messages it has left: the app cancels that chat's message notification at 0 and
 * shows the rest otherwise. Calls and "not sent" notices are never part of it.
 */
class ExpirySweeper(
    private val store: ChatStore,
    private val now: () -> Long,
    private val onRemoved: (ids: List<String>, unreadLeft: Map<String, Int>) -> Unit = { _, _ -> },
) {
    /** Sweeps once. Returns when the next message that is in the store's lists expires (epoch ms), or null when none will. */
    fun sweep(): Long? {
        val t = now()
        val incoming = HashMap<String, String>()
        val me = store.me.id
        for (chat in store.chats.value + store.requests.value) {
            for (m in chat.messages) {
                if (!Expiry.isExpired(m.expiresAt?.toEpochMilli(), t)) continue
                if (m.senderId != me && m.senderId != "system" && m.content !is MessageContent.System) incoming[m.id] = chat.id
            }
        }
        val removed = store.sweepExpired(t)
        if (removed.isNotEmpty()) {
            val left = HashMap<String, Int>()
            for (id in removed) incoming[id]?.let { chatId -> left[chatId] = store.chat(chatId)?.unreadCount ?: 0 }
            onRemoved(removed, left)
        }
        return (store.chats.value + store.requests.value).asSequence()
            .flatMap { it.messages.asSequence() }
            .mapNotNull { it.expiresAt?.toEpochMilli() }
            .filter { it > t }
            .minOrNull()
    }

    /**
     * Sweeps now, again at each next expiry, and whenever the chat lists change (a message with a timer arrived). Runs
     * until cancelled. Call it while the app is on screen; a start or a return to the foreground starts it again.
     */
    suspend fun run() {
        store.chats.collectLatest {
            while (true) {
                // Nothing else in the lists will expire. Older expired rows outside the cached window are deleted by the
                // next sweep (start or foreground) and are hidden by reads in the meantime.
                val next = sweep() ?: break
                // Re-read the clock now and then in case it was changed while waiting.
                delay((next - now()).coerceIn(1L, MAX_WAIT_MS))
            }
        }
    }

    private companion object {
        const val MAX_WAIT_MS = 15 * 60_000L
    }
}
