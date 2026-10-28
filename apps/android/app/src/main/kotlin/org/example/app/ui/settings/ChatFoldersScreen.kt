package org.example.app.ui.settings

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
        FormDialog(onDismiss = { renamingId = null }) {
            RenameFolderForm(folder.name, onSave = { store.renameFolder(folder.id, it); renamingId = null }, onCancel = { renamingId = null })
        }
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
        FormDialog(onDismiss = { creating = false }) {
            NewFolderForm(
                chats.map { it.id to (store.contact(it.contactId)?.displayName ?: it.contactId) },
                onCreate = { name, ids -> store.addFolder(name, ids); creating = false },
                onCancel = { creating = false },
            )
        }
    }
}
