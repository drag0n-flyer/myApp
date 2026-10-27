package org.example.app.ui.chatlist

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.chats.ChatFolder

@Composable
fun FolderChips(chips: List<FolderChip>, selectedId: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chips, key = { it.folder.id }) { chip ->
            val label = when (chip.folder.kind) {
                ChatFolder.Kind.All -> stringResource(R.string.folder_all)
                ChatFolder.Kind.Unread ->
                    if (chip.unreadCount > 0) stringResource(R.string.folder_unread_count, chip.unreadCount) else stringResource(R.string.folder_unread)
                ChatFolder.Kind.Custom -> chip.folder.name
            }
            FilterChip(selected = chip.folder.id == selectedId, onClick = { onSelect(chip.folder.id) }, label = { Text(label) })
        }
    }
}
