package org.example.app.ui.contacts

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.chats.ChatStore
import org.example.app.ui.chatlist.ChatRow
import org.example.app.ui.chatlist.ChatRowModel

/** First messages from people who are not contacts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageRequestsScreen(store: ChatStore, onOpenChat: (chatId: String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val requests by store.requests.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.requests_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        if (requests.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.requests_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(requests, key = { it.id }) { chat ->
                    val last = chat.lastMessage
                    val row = ChatRowModel(chat.id, store.contact(chat.contactId)?.displayName ?: chat.contactId, last?.preview, last?.sentAt, chat.unreadCount)
                    ChatRow(row, onClick = { onOpenChat(chat.id) })
                }
                item {
                    Text(
                        stringResource(R.string.requests_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
