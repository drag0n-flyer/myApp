package org.example.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.app.core.chats.ChatStore
import org.example.app.core.people.ExpirySweeper

/**
 * Disappearing messages while the app is on screen: sweeps when the app starts and each time it returns to the
 * foreground, then again at each next `expires_at`, and whenever a message with a timer arrives. Draws nothing.
 * [onRemoved] gets the IDs of the chats that lost messages, to take their delivered notifications away.
 */
@Composable
fun ExpirySweepEffect(
    store: ChatStore,
    now: () -> Long = System::currentTimeMillis,
    onRemoved: (chatIds: Set<String>) -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    val removed = rememberUpdatedState(onRemoved)
    LaunchedEffect(store, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // The sweep reads and writes the database: off the main thread.
            withContext(Dispatchers.IO) {
                ExpirySweeper(store, now) { _, chatIds -> if (chatIds.isNotEmpty()) removed.value(chatIds) }.run()
            }
        }
    }
}
