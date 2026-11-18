package org.example.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.time.Instant
import org.example.app.R
import org.example.app.core.people.PeerView
import org.example.app.core.people.PresenceLine
import org.example.app.ui.shortTime

/**
 * The line under a contact's name: "typing…", else "online", else "last seen" with the time, else nothing. In a group
 * ([PeerView.typers] not empty) it names the one who is typing, or counts two or more; [nameOf] gives a member's name.
 */
@Composable
fun peerLineText(view: PeerView?, nameOf: (String) -> String? = { null }): String? = when {
    view == null -> null
    view.typers.size == 1 -> stringResource(R.string.group_typing_one, nameOf(view.typers[0]) ?: stringResource(R.string.group_someone))
    view.typers.size > 1 -> stringResource(R.string.group_typing_many, view.typers.size)
    view.typing -> stringResource(R.string.signal_typing)
    else -> when (val p = view.presence) {
        PresenceLine.Result.Online -> stringResource(R.string.signal_online)
        is PresenceLine.Result.LastSeen -> stringResource(R.string.signal_last_seen, shortTime(Instant.ofEpochMilli(p.atMs)))
        PresenceLine.Result.None -> null
    }
}
