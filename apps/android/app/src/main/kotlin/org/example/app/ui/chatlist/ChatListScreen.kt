package org.example.app.ui.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel,
    onOpenChat: (String) -> Unit,
    onOpenRequests: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.tab_chats)) },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.mark_all_read)) },
                                onClick = { menuOpen = false; viewModel.markAllRead() },
                            )
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewChat) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.new_chat))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            FolderChips(state.folders, state.selectedFolderId, viewModel::select)
            StatusStrip(state.serverHost, state.modeKey, Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.weight(1f)) {
                if (state.requestCount > 0) {
                    item(key = "requests") {
                        val title = stringResource(R.string.requests_row, state.requestCount)
                        ListItem(
                            modifier = Modifier.clickable(onClick = onOpenRequests),
                            leadingContent = { Icon(painterResource(R.drawable.ic_inbox), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(title, color = MaterialTheme.colorScheme.primary) },
                        )
                    }
                }
                items(state.rows, key = { it.chatId }) { row -> ChatRow(row, onClick = { onOpenChat(row.chatId) }) }
            }
        }
    }
}
