package org.example.app.core.people

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.example.app.core.chats.ChatStore

/**
 * Removes expired messages from [store] and says when the next one expires. The clock is a parameter so a test can
 * drive it. [onRemoved] gets the removed message IDs and the IDs of the chats they were in, so the app can take their
 * delivered notifications away.
 */
class ExpirySweeper(
    private val store: ChatStore,
    private val now: () -> Long,
    private val onRemoved: (ids: List<String>, chatIds: Set<String>) -> Unit = { _, _ -> },
) {
    /** Sweeps once. Returns when the next message that is in the store's lists expires (epoch ms), or null when none will. */
    fun sweep(): Long? {
        val t = now()
        val owner = HashMap<String, String>()
        for (chat in store.chats.value + store.requests.value) {
            for (m in chat.messages) if (Expiry.isExpired(m.expiresAt?.toEpochMilli(), t)) owner[m.id] = chat.id
        }
        val removed = store.sweepExpired(t)
        if (removed.isNotEmpty()) onRemoved(removed, removed.mapNotNullTo(HashSet()) { owner[it] })
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
