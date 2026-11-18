package org.example.app.ui.chatlist

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel,
    onOpenChat: (String) -> Unit,
    onOpenRequests: () -> Unit,
    onNewChat: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenArchive: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberChatRowActions(viewModel, snackbar)
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.tab_chats)) },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.search_action))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.mark_all_read)) },
                                onClick = { menuOpen = false; viewModel.markAllRead() },
                                modifier = Modifier.semantics { role = Role.Button },
                            )
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewChat) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.new_chat))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            FolderChips(state.folders, state.selectedFolderId, viewModel::select)
            StatusStrip(state.serverHost, state.modeKey, state.status, Modifier.padding(top = 8.dp))
            if (state.rows.isEmpty() && state.requestCount == 0 && state.archivedRows.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(stringResource(R.string.chat_list_empty), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onNewChat, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.new_chat)) }
                }
                return@Column
            }
            LazyColumn(Modifier.weight(1f)) {
                if (state.requestCount > 0) {
                    item(key = "requests") {
                        val title = stringResource(R.string.requests_row, state.requestCount)
                        ListItem(
                            modifier = Modifier.clickable(role = Role.Button, onClick = onOpenRequests),
                            leadingContent = { Icon(painterResource(R.drawable.ic_inbox), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(title, color = MaterialTheme.colorScheme.primary) },
                        )
                    }
                }
                if (state.archivedRows.isNotEmpty()) {
                    item(key = "archived") {
                        // One item for a screen reader: "Archived, N".
                        val archivedLabel = stringResource(R.string.archived_row) + ", " + state.archivedRows.size
                        ListItem(
                            modifier = Modifier.clickable(role = Role.Button, onClick = onOpenArchive)
                                .clearAndSetSemantics {
                                    contentDescription = archivedLabel
                                    role = Role.Button
                                    onClick { onOpenArchive(); true }
                                },
                            leadingContent = { Icon(painterResource(R.drawable.ic_archive), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            headlineContent = { Text(stringResource(R.string.archived_row)) },
                            trailingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    // Unread chats in the archive, in a neutral colour: archived chats do not shout.
                                    if (state.archivedUnread > 0) UnreadBadge(state.archivedUnread, muted = true)
                                    Text(state.archivedRows.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                        )
                    }
                }
                items(state.rows, key = { it.chatId }) { row ->
                    SwipeableChatRow(row, inArchive = false, actions = actions, onClick = { onOpenChat(row.chatId) })
                }
            }
        }
    }
}

/** The row actions for a list screen: a refused sixth pin shows "You can pin up to 5 chats." in [snackbar]. */
@Composable
internal fun rememberChatRowActions(viewModel: ChatListViewModel, snackbar: SnackbarHostState): ChatRowActions {
    val scope = rememberCoroutineScope()
    val pinLimit = stringResource(R.string.pin_limit)
    return remember(viewModel, snackbar, pinLimit) {
        ChatRowActions(
            setPinned = viewModel::setPinned,
            setMuted = viewModel::setMuted,
            setArchived = viewModel::setArchived,
            markRead = viewModel::markRead,
            onPinRefused = { scope.launch { snackbar.showSnackbar(pinLimit) } },
        )
    }
}
