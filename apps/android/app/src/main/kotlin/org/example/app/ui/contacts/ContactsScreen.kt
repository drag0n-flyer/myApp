package org.example.app.ui.contacts

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextAlign
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
import org.example.app.ui.usernameText
import org.example.app.ui.Avatar

/** Invite and Add rows, then contacts in alphabetical sections. Opening a contact opens (or creates) their chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    store: ChatStore,
    /** Null hides the invite row (no invite link yet). */
    inviteUrl: String?,
    onOpenChat: (chatId: String) -> Unit,
    onAddContact: () -> Unit,
    onInvite: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contacts by store.contacts.collectAsStateWithLifecycle()
    val locale = LocalLocale.current.platformLocale
    val collator = remember(locale) { java.text.Collator.getInstance(locale) }
    val sections = remember(contacts, locale) {
        contacts.sortedWith(compareBy(collator) { it.displayName }).groupBy { sectionLetter(it.displayName, locale) }
            .toSortedMap(compareBy<String> { it == "#" }.thenComparing(collator))
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text(stringResource(R.string.tab_contacts)) }, scrollBehavior = scroll) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            if (inviteUrl != null) item {
                ListItem(
                    modifier = Modifier.clickable(onClickLabel = stringResource(R.string.contacts_invite), role = Role.Button) { onInvite(inviteUrl) },
                    leadingContent = { ActionCircle(R.drawable.ic_share) },
                    headlineContent = { Text(stringResource(R.string.contacts_invite), style = MaterialTheme.typography.titleMedium) },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button, onClick = onAddContact),
                    leadingContent = { ActionCircle(R.drawable.ic_add) },
                    headlineContent = { Text(stringResource(R.string.contacts_add), style = MaterialTheme.typography.titleMedium) },
                )
            }
            if (sections.isEmpty()) item(key = "empty") {
                Text(
                    stringResource(R.string.contacts_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            for ((letter, people) in sections) {
                item(key = "h-$letter") {
                    Text(letter, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                items(people, key = { it.id }) { person ->
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Button) { onOpenChat(store.ensureChat(person.id)) },
                        leadingContent = { Avatar(person.displayName, colorKey = person.id) },
                        headlineContent = { Text(person.displayName, maxLines = 1, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = { Text(usernameText(person.username)) },
                    )
                }
            }
            if (contacts.isNotEmpty()) item(key = "count") {
                Text(
                    pluralStringResource(R.plurals.contacts_count, contacts.size, contacts.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

/**
 * The section a name sorts under: its first letter without accents ("Émile" under "E"), taken by code point so an
 * emoji is never split. Anything that does not start with a letter (emoji, digits, symbols) goes under "#".
 * Only Latin, Greek and Cyrillic are decomposed, so Hangul and kana keep their own first character.
 */
internal fun sectionLetter(name: String, locale: java.util.Locale): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "#"
    val first = trimmed.codePointAt(0)
    if (!Character.isLetter(first)) return "#"
    val script = Character.UnicodeScript.of(first)
    val base = if (script == Character.UnicodeScript.LATIN || script == Character.UnicodeScript.GREEK || script == Character.UnicodeScript.CYRILLIC) {
        val stripped = java.text.Normalizer.normalize(String(Character.toChars(first)), java.text.Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        if (stripped.isEmpty()) first else stripped.codePointAt(0)
    } else first
    return String(Character.toChars(base)).uppercase(locale)
}

/** The round tinted icon of the Invite and Add rows; it matches the size of a contact's photo. */
@Composable
private fun ActionCircle(@androidx.annotation.DrawableRes icon: Int) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
