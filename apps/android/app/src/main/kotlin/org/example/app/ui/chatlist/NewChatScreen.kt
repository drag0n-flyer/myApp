package org.example.app.ui.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.example.app.R
import org.example.app.core.chats.ChatStore
import org.example.app.ui.Avatar
import org.example.app.ui.usernameText

/**
 * The contact picker behind the new-chat button: an "Add contact" row, then the contacts by name. Choosing one calls
 * [onChoose] (the caller makes the chat with `ensureChat` and opens it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(
    store: ChatStore,
    onChoose: (contactId: String) -> Unit,
    onAddContact: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contacts by store.contacts.collectAsStateWithLifecycle()
    val locale = LocalLocale.current.platformLocale
    val sorted = remember(contacts, locale) {
        val collator = java.text.Collator.getInstance(locale)
        contacts.sortedWith(compareBy(collator) { it.displayName })
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_chat)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button, onClick = onAddContact),
                    leadingContent = { Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(stringResource(R.string.contacts_add), color = MaterialTheme.colorScheme.primary) },
                )
            }
            if (sorted.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.contacts_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(sorted, key = { it.id }) { person ->
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button) { onChoose(person.id) },
                    leadingContent = { Avatar(person.displayName) },
                    headlineContent = { Text(person.displayName, maxLines = 1) },
                    supportingContent = { Text(usernameText(person.username)) },
                )
            }
        }
    }
}
