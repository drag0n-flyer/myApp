package org.example.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.calls.DataFormat
import org.example.app.ui.dataSizeText
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.MediaState
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.MessagePreview
import org.example.app.core.settings.MediaKind
import org.example.app.ui.previewText
import org.example.app.ui.shortTime

/** What a message in the chat needs from outside: who is "you", who it replies to, and the download rule. */
@Composable
fun MessageBubble(
    message: Message,
    isOwn: Boolean,
    replyTo: Message?,
    replyAuthor: String?,
    autoDownloads: (MediaKind) -> Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = message.content
    if (content is MessageContent.System) {
        Box(modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(previewText(message.preview), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val own = MaterialTheme.colorScheme.primaryContainer
    val other = MaterialTheme.colorScheme.surfaceContainerHigh
    val shape = RoundedCornerShape(18.dp, 18.dp, if (isOwn) 4.dp else 18.dp, if (isOwn) 18.dp else 4.dp)
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start) {
        Surface(shape = shape, color = if (isOwn) own else other, modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (replyTo != null) ReplyQuote(replyTo, replyAuthor)
                Body(message, autoDownloads, onDownload)
                if (message.reactions.isNotEmpty()) Text(message.reactions.joinToString(" "))
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (message.editedAt != null) Text(stringResource(R.string.edited), style = MaterialTheme.typography.labelSmall)
                    Text(shortTime(message.sentAt), style = MaterialTheme.typography.labelSmall)
                    if (isOwn) Ticks(message.status)
                }
            }
        }
    }
}

@Composable
private fun ReplyQuote(reply: Message, author: String?) {
    val text = previewText(reply.preview)
    val description = stringResource(R.string.reply_to, author ?: stringResource(R.string.you)) + ": " + text
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(width = 3.dp, height = 32.dp).background(MaterialTheme.colorScheme.primary))
        Text(text, maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Ticks(status: DeliveryStatus) {
    val label = stringResource(
        when (status) {
            DeliveryStatus.Sending -> R.string.delivery_sending
            DeliveryStatus.Sent -> R.string.delivery_sent
            DeliveryStatus.Delivered -> R.string.delivery_delivered
            DeliveryStatus.Read -> R.string.delivery_read
        },
    )
    val icon = if (status == DeliveryStatus.Sent || status == DeliveryStatus.Sending) R.drawable.ic_check else R.drawable.ic_done_all
    val tint = if (status == DeliveryStatus.Read) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(14.dp))
}

@Composable
private fun Body(message: Message, autoDownloads: (MediaKind) -> Boolean, onDownload: () -> Unit) {
    when (val c = message.content) {
        is MessageContent.Text -> Text(c.text)
        is MessageContent.Voice -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Voice messages download by themselves on every network, so there is no download button.
            Icon(painterResource(R.drawable.ic_play), contentDescription = stringResource(R.string.play_voice))
            Text((message.preview as? MessagePreview.Resource)?.args?.firstOrNull().orEmpty())
        }
        is MessageContent.Photo -> MediaTile(
            title = stringResource(R.string.photo_with_size, dataSizeText(DataFormat.size(c.bytes))),
            media = message.media,
            wait = !autoDownloads(MediaKind.Photo),
            blurred = true,
            onDownload = onDownload,
        )
        is MessageContent.File -> MediaTile(
            title = stringResource(R.string.file_with_size, c.name, dataSizeText(DataFormat.size(c.bytes))),
            media = message.media,
            wait = !autoDownloads(MediaKind.File),
            blurred = false,
            onDownload = onDownload,
        )
        is MessageContent.Call, MessageContent.Deleted, MessageContent.Unsupported ->
            Text(previewText(message.preview), style = MaterialTheme.typography.bodyMedium)
        is MessageContent.System -> Unit
    }
    if (message.undecryptable) Text(stringResource(R.string.cannot_open_message), style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun MediaTile(title: String, media: MediaState?, wait: Boolean, blurred: Boolean, onDownload: () -> Unit) {
    val phase = media?.phase ?: MediaState.Phase.Remote
    val downloadingLabel = stringResource(R.string.downloading)
    val needsTap = phase is MediaState.Phase.Failed || (phase is MediaState.Phase.Remote && wait)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 120.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // Stand-in for the blurred preview; the real image is loaded only after the download.
            if (blurred && needsTap) {
                Box(Modifier.size(96.dp).blur(16.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp)))
            }
            when {
                needsTap -> IconButton(onClick = onDownload) {
                    Icon(painterResource(R.drawable.ic_download), contentDescription = stringResource(R.string.download))
                }
                phase is MediaState.Phase.Downloading -> CircularProgressIndicator(
                    progress = { phase.progress.toFloat() },
                    modifier = Modifier.semantics { contentDescription = downloadingLabel }.size(32.dp),
                )
            }
        }
        Text(title, style = MaterialTheme.typography.labelMedium)
    }
}
