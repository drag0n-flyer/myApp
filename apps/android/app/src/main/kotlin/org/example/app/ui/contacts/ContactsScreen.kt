package org.example.app.ui.contacts

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLocale
import org.example.app.R
import org.example.app.core.chats.ChatStore
import org.example.app.ui.Avatar

/** Invite and Add rows, then contacts in alphabetical sections. Opening a contact opens (or creates) their chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    store: ChatStore,
    inviteUrl: String,
    onOpenChat: (chatId: String) -> Unit,
    onAddContact: () -> Unit,
    onInvite: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contacts by store.contacts.collectAsStateWithLifecycle()
    val locale = LocalLocale.current.platformLocale
    val collator = remember(locale) { java.text.Collator.getInstance(locale) }
    val sections = remember(contacts, locale) {
        contacts.sortedWith(compareBy(collator) { it.displayName }).groupBy { it.displayName.take(1).uppercase(locale) }.toSortedMap(collator)
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text(stringResource(R.string.tab_contacts)) }, scrollBehavior = scroll) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button) { onInvite(inviteUrl) },
                    leadingContent = { Icon(painterResource(R.drawable.ic_contacts), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(stringResource(R.string.contacts_invite), color = MaterialTheme.colorScheme.primary) },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button, onClick = onAddContact),
                    leadingContent = { Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(stringResource(R.string.contacts_add), color = MaterialTheme.colorScheme.primary) },
                )
            }
            for ((letter, people) in sections) {
                item(key = "h-$letter") {
                    Text(letter, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
                }
                items(people, key = { it.id }) { person ->
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Button) { onOpenChat(store.ensureChat(person.id)) },
                        leadingContent = { Avatar(person.displayName) },
                        headlineContent = { Text(person.displayName, maxLines = 1) },
                        supportingContent = { Text("@${person.username}") },
                    )
                }
            }
        }
    }
}
