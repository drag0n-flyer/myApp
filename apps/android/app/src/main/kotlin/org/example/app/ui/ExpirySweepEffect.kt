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
 * [onUnreadLeft] gets, for each chat that lost an incoming message, its unread count after the sweep (see ExpirySweeper).
 */
@Composable
fun ExpirySweepEffect(
    store: ChatStore,
    now: () -> Long = System::currentTimeMillis,
    onUnreadLeft: (Map<String, Int>) -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    val removed = rememberUpdatedState(onUnreadLeft)
    LaunchedEffect(store, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // The sweep reads and writes the database: off the main thread.
            withContext(Dispatchers.IO) {
                ExpirySweeper(store, now) { _, left -> if (left.isNotEmpty()) removed.value(left) }.run()
            }
        }
    }
}
