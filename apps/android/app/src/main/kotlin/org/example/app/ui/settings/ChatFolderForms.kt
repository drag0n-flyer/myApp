package org.example.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.app.R

/** A dialog window around a form. The forms are plain composables so tests can use them without a window. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FormDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = AlertDialogDefaults.shape, tonalElevation = AlertDialogDefaults.TonalElevation) { content() }
    }
}

@Composable
private fun FormButtons(confirmLabel: String, confirmEnabled: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        TextButton(enabled = confirmEnabled, onClick = onConfirm) { Text(confirmLabel) }
    }
}

/** The rename body. The typed text survives rotation. */
@Composable
fun RenameFolderForm(currentName: String, onSave: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.folders_rename), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.folders_name)) }, singleLine = true)
        FormButtons(stringResource(R.string.folders_save), name.isNotBlank(), { onSave(name.trim()) }, onCancel)
    }
}

/** The create body: a name and the chats to put in the folder. Both survive rotation. */
@Composable
fun NewFolderForm(chats: List<Pair<String, String>>, onCreate: (name: String, chatIds: Set<String>) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf("") }
    val chosen = rememberSaveable(saver = listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf<String>() }
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.folders_new), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.folders_name)) }, singleLine = true)
        SectionHeader(stringResource(R.string.folders_chats))
        LazyColumn(Modifier.heightIn(max = 240.dp)) {
            items(chats, key = { it.first }) { (id, label) ->
                SwitchRow(label, id in chosen) { v -> if (v) chosen.add(id) else chosen.remove(id) }
            }
        }
        FormButtons(stringResource(R.string.folders_create), name.isNotBlank(), { onCreate(name.trim(), chosen.toSet()) }, onCancel)
    }
}
