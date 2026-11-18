package org.example.app.ui.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import org.example.app.R
import org.example.app.core.account.ConnectionStatus
import org.example.app.ui.keyString

/**
 * One line under the app bar. Connected: a green dot, the server name and the mode a new call would start with.
 * Otherwise a grey dot and "Connecting…" or "No connection".
 */
@Composable
fun StatusStrip(host: String, modeKey: String, status: ConnectionStatus = ConnectionStatus.Connected, modifier: Modifier = Modifier) {
    val mode = keyString(modeKey)
    val label = when (status) {
        ConnectionStatus.Connected -> stringResource(R.string.status_connected_to, host)
        ConnectionStatus.Connecting -> stringResource(R.string.status_connecting)
        ConnectionStatus.Offline -> stringResource(R.string.status_offline)
    }
    val dot = if (status == ConnectionStatus.Connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).background(dot, CircleShape).clearAndSetSemantics {})
            if (status == ConnectionStatus.Connected) {
                Text(host, style = MaterialTheme.typography.labelMedium)
                Text("· $mode", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
