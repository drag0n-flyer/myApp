package org.example.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.chats.MediaState
import org.example.app.core.chats.MessageContent
import org.example.app.core.settings.MediaKind
import org.example.app.ui.Avatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    myId: String,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    onCall: (video: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onAttach: (MediaKind) -> Unit = {},
    onRecordVoice: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val list = rememberLazyListState()
    // Newest at the bottom: the list is reversed, so index 0 is the newest message.
    val newestFirst = state.messages.asReversed()
    val byId = remember(state.messages) { state.messages.associateBy { it.id } }
    // Follow new messages only when already at the newest one, or when the user sent it.
    val newest = newestFirst.firstOrNull()
    var previousNewestId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(newest?.id) {
        if (newest != null) {
            val first = list.layoutInfo.visibleItemsInfo.firstOrNull()
            val atNewest = previousNewestId == null ||
                (first?.key == previousNewestId && list.firstVisibleItemScrollOffset == 0)
            if (atNewest || newest.senderId == myId) list.scrollToItem(0)
            previousNewestId = newest.id
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back)) }
                },
                title = {
                    Row(Modifier.clickable(onClick = onOpenInfo), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(state.contactName, size = 36.dp)
                        Text(state.contactName, maxLines = 1)
                    }
                },
                actions = {
                    IconButton(onClick = { onCall(false) }) { Icon(painterResource(R.drawable.ic_call), contentDescription = stringResource(R.string.call_audio)) }
                    IconButton(onClick = { onCall(true) }) { Icon(painterResource(R.drawable.ic_videocam), contentDescription = stringResource(R.string.call_video)) }
                },
            )
        },
        bottomBar = {
            Composer(enabled = !state.sendingPaused, onSend = { viewModel.send(it) }, onAttach = onAttach, onRecordVoice = onRecordVoice)
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), state = list, reverseLayout = true, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(newestFirst, key = { it.id }) { message ->
                val reply = message.replyToId?.let { byId[it] }
                val autoKind = when (message.content) {
                    is MessageContent.Photo -> MediaKind.Photo
                    is MessageContent.File -> MediaKind.File
                    else -> null
                }
                // Once per message: keyed on the id, so recomposition and scrolling never retrigger it.
                LaunchedEffect(message.id) {
                    val waiting = (message.media?.phase ?: MediaState.Phase.Remote) is MediaState.Phase.Remote
                    if (autoKind != null && waiting && viewModel.autoDownloads(autoKind)) viewModel.download(message.id)
                }
                MessageBubble(
                    message = message,
                    isOwn = message.senderId == myId,
                    replyTo = reply,
                    replyAuthor = reply?.let { if (it.senderId == myId) null else state.contactName },
                    autoDownloads = viewModel::autoDownloads,
                    onDownload = { viewModel.download(message.id) },
                )
            }
        }
    }
}
