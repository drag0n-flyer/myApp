package org.example.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import org.example.app.R
import org.example.app.ui.Avatar
import org.example.app.ui.usernameText

/** Search from the chat list: people first, then messages. Tapping a person opens the chat; a message opens it at that message. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenContact: (contactId: String) -> Unit,
    onOpenMessage: (chatId: String, messageId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The text lives here so it survives rotation; the view model only holds the results.
    var text by rememberSaveable { mutableStateOf(state.query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        if (text.isNotEmpty()) viewModel.setQuery(text)
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = text,
                        onValueChange = { text = it; viewModel.setQuery(it) },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = MaterialTheme.colorScheme.surface,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.setQuery(text) }),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        if (state.searched && state.rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.search_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                itemsIndexed(state.rows, key = { index, row -> rowKey(index, row) }) { _, row ->
                    when (row) {
                        is SearchRow.Contact -> ListItem(
                            modifier = Modifier.clickable(role = Role.Button) { onOpenContact(row.contact.id) },
                            leadingContent = { Avatar(row.contact.displayName, colorKey = row.contact.id) },
                            headlineContent = { Text(row.contact.displayName, maxLines = 1) },
                            supportingContent = { Text(usernameText(row.contact.username)) },
                        )
                        is SearchRow.Message -> ListItem(
                            modifier = Modifier.clickable(role = Role.Button) { onOpenMessage(row.result.hit.chatId, row.result.hit.messageId) },
                            leadingContent = { Avatar(row.result.chatName, colorKey = row.result.colorKey) },
                            headlineContent = { Text(row.result.chatName, maxLines = 1) },
                            supportingContent = { Text(row.result.hit.preview, maxLines = 2) },
                        )
                    }
                }
            }
        }
    }
}

private fun rowKey(index: Int, row: SearchRow): String = when (row) {
    is SearchRow.Contact -> "c-${row.contact.id}"
    is SearchRow.Message -> "m-${row.result.hit.messageId}-$index"
}
