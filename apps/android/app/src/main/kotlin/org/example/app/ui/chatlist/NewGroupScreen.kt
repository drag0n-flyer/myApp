package org.example.app.ui.chatlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.example.app.R
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.GroupCreateFailed
import org.example.app.core.chats.GroupCreated
import org.example.app.core.groups.GroupNameRule
import org.example.app.ui.Avatar
import org.example.app.ui.usernameText

/**
 * New group, in two steps: "Add people" (contacts to tick, at least 2) and then the name and "Create". A person who could
 * not be added yet is listed after the group exists. [onCreated] gets the new chat's ID. No group photo is chosen here yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupScreen(store: ChatStore, onCreated: (chatId: String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val contacts by store.contacts.collectAsStateWithLifecycle()
    val locale = LocalLocale.current.platformLocale
    val sorted = remember(contacts, locale) {
        val collator = java.text.Collator.getInstance(locale)
        contacts.sortedWith(compareBy(collator) { it.displayName })
    }
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<GroupCreateFailed.Reason?>(null) }
    var told by remember { mutableStateOf<GroupCreated?>(null) }
    val scope = rememberCoroutineScope()

    val canContinue = picked.size >= MIN_PEOPLE
    val cleanName = GroupNameRule.clean(name)

    fun create() {
        if (busy || cleanName == null) return
        busy = true
        failure = null
        scope.launch {
            try {
                val made = store.createGroup(picked, cleanName, null)
                if (made.notAdded.isEmpty()) onCreated(made.chatId) else told = made
            } catch (e: GroupCreateFailed) {
                failure = e.reason
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (naming) R.string.group_new else R.string.group_add_people)) },
                navigationIcon = {
                    IconButton(onClick = { if (naming && !busy) naming = false else if (!busy) onBack() }) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (!naming) {
            Column(Modifier.padding(padding)) {
                LazyColumn(Modifier.weight(1f)) {
                    items(sorted, key = { it.id }) { person ->
                        val on = person.id in picked
                        ListItem(
                            modifier = Modifier.toggleable(value = on, role = Role.Checkbox) {
                                picked = if (it) picked + person.id else picked - person.id
                            },
                            leadingContent = { Avatar(person.displayName, colorKey = person.id) },
                            headlineContent = { Text(person.displayName, maxLines = 1) },
                            supportingContent = { Text(usernameText(person.username)) },
                            trailingContent = { Checkbox(checked = on, onCheckedChange = null) },
                        )
                    }
                }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!canContinue) {
                        Text(stringResource(R.string.group_choose_two), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { naming = true }, enabled = canContinue, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ob_continue))
                    }
                }
            }
        } else {
            Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = capName(it) },
                    label = { Text(stringResource(R.string.group_name)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                failure?.let {
                    Text(
                        stringResource(failureText(it)), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Button(onClick = ::create, enabled = cleanName != null && !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.group_create))
                }
            }
        }
    }

    told?.let { made ->
        AlertDialog(
            onDismissRequest = {},
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    made.notAdded.forEach { id ->
                        Text(stringResource(R.string.group_not_added, store.contact(id)?.displayName ?: stringResource(R.string.group_someone)))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { told = null; onCreated(made.chatId) }) { Text(stringResource(R.string.group_ok)) } },
        )
    }
}

private const val MIN_PEOPLE = 2

/** The field never holds more than the 50 code points a name may have. */
private fun capName(text: String): String =
    if (text.codePointCount(0, text.length) <= GroupNameRule.MAX) text else text.substring(0, text.offsetByCodePoints(0, GroupNameRule.MAX))

private fun failureText(reason: GroupCreateFailed.Reason): Int = when (reason) {
    GroupCreateFailed.Reason.NobodyReachable -> R.string.group_create_nobody
    GroupCreateFailed.Reason.NotFound -> R.string.group_create_not_found
    GroupCreateFailed.Reason.Full -> R.string.group_create_full
    GroupCreateFailed.Reason.Other -> R.string.group_create_failed
}
