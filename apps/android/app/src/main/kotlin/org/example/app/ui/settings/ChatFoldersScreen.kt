package org.example.app.ui.settings

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.example.app.R
import org.example.app.core.chats.ChatFolder
import org.example.app.core.chats.ChatStore

/** Create, rename, reorder and delete custom folders. All and Unread are fixed. Reordering uses Move buttons, which TalkBack can reach. */
@Composable
fun ChatFoldersScreen(store: ChatStore, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val folders by store.folders.collectAsStateWithLifecycle()
    val chats by store.chats.collectAsStateWithLifecycle()
    val custom = folders.filter { it.kind == ChatFolder.Kind.Custom }
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    val renaming = folders.firstOrNull { it.id == renamingId }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val deleting = folders.firstOrNull { it.id == deletingId }
    val renameLabel = stringResource(R.string.folders_rename)

    SettingsPage(stringResource(R.string.settings_folders), onBack, modifier) {
        items(folders.filter { it.kind != ChatFolder.Kind.Custom }, key = { it.id }) { folder ->
            ListItem(headlineContent = { Text(if (folder.kind == ChatFolder.Kind.All) stringResource(R.string.folder_all) else stringResource(R.string.folder_unread)) })
        }
        item { FooterText(stringResource(R.string.folders_fixed_footer)) }
        itemsIndexed(custom, key = { _, it -> it.id }) { index, folder ->
            ListItem(
                modifier = Modifier.clickable(onClickLabel = renameLabel, role = Role.Button) { renamingId = folder.id },
                headlineContent = { Text(folder.name) },
                trailingContent = {
                    Row {
                        if (index > 0) IconButton(onClick = { store.moveFolder(folder.id, index - 1) }) {
                            Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = stringResource(R.string.folders_move_up, folder.name))
                        }
                        if (index < custom.lastIndex) IconButton(onClick = { store.moveFolder(folder.id, index + 1) }) {
                            Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = stringResource(R.string.folders_move_down, folder.name))
                        }
                        IconButton(onClick = { deletingId = folder.id }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.folders_delete, folder.name))
                        }
                    }
                },
            )
        }
        item {
            ListItem(
                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.folders_new), role = Role.Button) { creating = true },
                leadingContent = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.folders_new)) },
            )
        }
    }

    renaming?.let { folder ->
        var name by rememberSaveable(folder.id) { mutableStateOf(folder.name) }
        AlertDialog(
            onDismissRequest = { renamingId = null },
            title = { Text(stringResource(R.string.folders_rename)) },
            text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.folders_name)) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { store.renameFolder(folder.id, name.trim()); renamingId = null }) {
                    Text(stringResource(R.string.folders_save))
                }
            },
            dismissButton = { TextButton(onClick = { renamingId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { folder ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text(stringResource(R.string.folders_delete_title)) },
            text = { Text(stringResource(R.string.folders_delete_body, folder.name)) },
            confirmButton = { TextButton(onClick = { store.deleteFolder(folder.id); deletingId = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (creating) {
        var name by rememberSaveable { mutableStateOf("") }
        val chosen = rememberSaveable(saver = listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf<String>() }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.folders_new)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.folders_name)) }, singleLine = true)
                    SectionHeader(stringResource(R.string.folders_chats))
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(chats, key = { it.id }) { chat ->
                            val on = chat.id in chosen
                            SwitchRow(store.contact(chat.contactId)?.displayName ?: chat.contactId, on) { v -> if (v) chosen.add(chat.id) else chosen.remove(chat.id) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { store.addFolder(name.trim(), chosen.toSet()); creating = false }) {
                    Text(stringResource(R.string.folders_create))
                }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
