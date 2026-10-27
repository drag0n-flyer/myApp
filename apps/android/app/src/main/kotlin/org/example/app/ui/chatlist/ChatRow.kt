package org.example.app.ui.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.material3.Badge
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import org.example.app.R
import org.example.app.ui.Avatar
import org.example.app.ui.previewText
import org.example.app.ui.shortTime
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp

@Composable
fun ChatRow(row: ChatRowModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val preview = row.preview?.let { previewText(it) }.orEmpty()
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        leadingContent = { Avatar(row.name) },
        headlineContent = { Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                row.time?.let { Text(shortTime(it), style = MaterialTheme.typography.labelSmall) }
                if (row.unreadCount > 0) {
                    val description = stringResource(R.string.unread_badge, row.unreadCount)
                    Badge(Modifier.semantics { contentDescription = description }) { Text(row.unreadCount.toString()) }
                }
            }
        },
    )
}
