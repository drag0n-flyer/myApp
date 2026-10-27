package org.example.app.ui.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.example.app.R
import org.example.app.core.chats.CallSummary
import org.example.app.core.chats.Chat
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.MessageContent
import org.example.app.ui.Avatar
import org.example.app.ui.shortTime

/** One line of call history, taken from the call messages in chats. */
data class CallRecord(
    val id: String,
    val contactId: String,
    val name: String,
    val summary: CallSummary,
    val at: Instant,
    val isOutgoing: Boolean,
) {
    val isMissed: Boolean get() = summary == CallSummary.MissedAudio || summary == CallSummary.MissedVideo
    val isVideo: Boolean get() = summary == CallSummary.MissedVideo || summary is CallSummary.Video
}

class CallHistoryViewModel(private val store: ChatStore) : ViewModel() {
    private val _missedOnly = MutableStateFlow(false)
    val missedOnly: StateFlow<Boolean> = _missedOnly

    /** Calls recorded in chats and message requests, newest first. */
    val records: StateFlow<List<CallRecord>> =
        combine(store.chats, store.requests) { chats, requests -> read(chats + requests) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, read(store.chats.value + store.requests.value))

    fun setMissedOnly(value: Boolean) { _missedOnly.value = value }

    private fun read(chats: List<Chat>): List<CallRecord> =
        chats.flatMap { chat ->
            val name = store.contact(chat.contactId)?.displayName ?: chat.contactId
            chat.messages.mapNotNull { message ->
                val content = message.content as? MessageContent.Call ?: return@mapNotNull null
                CallRecord(message.id, chat.contactId, name, content.summary, message.sentAt, message.senderId == store.me.id)
            }
        }.sortedByDescending { it.at }
}

/** The Calls tab: history with All and Missed chips, and a call-back button on each row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryScreen(
    viewModel: CallHistoryViewModel,
    onCall: (contactId: String, video: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val records by viewModel.records.collectAsState()
    val missedOnly by viewModel.missedOnly.collectAsState()
    val shown = if (missedOnly) records.filter { it.isMissed } else records
    Scaffold(modifier, topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_calls)) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "chips") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !missedOnly, onClick = { viewModel.setMissedOnly(false) }, label = { Text(stringResource(R.string.calls_filter_all)) })
                    FilterChip(selected = missedOnly, onClick = { viewModel.setMissedOnly(true) }, label = { Text(stringResource(R.string.calls_filter_missed)) })
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.calls_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(shown, key = { it.id }) { record -> CallRow(record, onCall) }
        }
    }
}

@Composable
private fun CallRow(record: CallRecord, onCall: (String, Boolean) -> Unit) {
    val color = if (record.isMissed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val direction = when {
        record.summary == CallSummary.MissedAudio -> stringResource(R.string.preview_missed_audio_call)
        record.summary == CallSummary.MissedVideo -> stringResource(R.string.preview_missed_video_call)
        record.isOutgoing -> stringResource(R.string.calls_outgoing)
        else -> stringResource(R.string.calls_incoming)
    }
    ListItem(
        leadingContent = { Avatar(record.name, size = 40.dp) },
        headlineContent = { Text(record.name, color = color, maxLines = 1) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    painterResource(if (record.isVideo) R.drawable.ic_videocam else R.drawable.ic_call),
                    contentDescription = null, modifier = Modifier.size(14.dp), tint = color,
                )
                Text(direction, color = if (record.isMissed) color else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(shortTime(record.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { onCall(record.contactId, record.isVideo) }) {
                    Icon(
                        painterResource(if (record.isVideo) R.drawable.ic_videocam else R.drawable.ic_call),
                        contentDescription = stringResource(R.string.calls_call_back, record.name),
                    )
                }
            }
        },
    )
}
