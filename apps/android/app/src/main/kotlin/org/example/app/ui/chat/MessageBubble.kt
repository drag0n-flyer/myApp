package org.example.app.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import org.example.app.R
import org.example.app.core.calls.DataFormat
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.LinkFinder
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.MessagePreview
import org.example.app.core.settings.MediaKind
import org.example.app.ui.dataSizeText
import org.example.app.ui.media.FileBubble
import org.example.app.ui.media.MediaHost
import org.example.app.ui.media.PhotoBubble
import org.example.app.ui.media.VoiceBubble
import org.example.app.ui.previewText

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
    /** Opens the message menu; null for no menu. */
    onLongPress: (() -> Unit)? = null,
    /** The menu itself, placed over the bubble (a `DropdownMenu`). */
    menu: @Composable () -> Unit = {},
    /** Tap on the quote: jump to the message it quotes. */
    onQuoteClick: () -> Unit = {},
    /** "Try again" on a message that was not sent. */
    onRetry: () -> Unit = {},
    /** Briefly true after a jump to this message. */
    highlighted: Boolean = false,
    /** What photo, file and voice bubbles need from the screen; null shows them without opening anything. */
    media: MediaHost? = null,
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
    val base = if (isOwn) own else other
    val fill by animateColorAsState(if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else base, label = "highlight")
    val shape = RoundedCornerShape(18.dp, 18.dp, if (isOwn) 4.dp else 18.dp, if (isOwn) 18.dp else 4.dp)
    val optionsLabel = stringResource(R.string.chat_message_options)
    val press = if (onLongPress == null) Modifier else Modifier
        .pointerInput(onLongPress) { detectTapGestures(onLongPress = { onLongPress() }) }
        .semantics { onLongClick(optionsLabel) { onLongPress(); true } }
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start) {
        Box {
            // The flash after a jump is a fill and an outline, so it shows on both the own and the other bubble colour.
            val outline = if (highlighted) BorderStroke(3.dp, MaterialTheme.colorScheme.tertiary) else null
            Surface(shape = shape, color = fill, border = outline, modifier = Modifier.widthIn(max = 300.dp).then(press)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (message.replyToId != null) ReplyQuote(replyTo, replyAuthor, onQuoteClick)
                    Body(message, isOwn, autoDownloads, onDownload, media)
                    if (message.reactions.isNotEmpty()) Text(message.reactions.joinToString(" "))
                    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (message.editedAt != null) Text(stringResource(R.string.edited), style = MaterialTheme.typography.labelSmall)
                        Text(clockTime(message.sentAt), style = MaterialTheme.typography.labelSmall)
                        if (isOwn) Ticks(message.status)
                    }
                    if (isOwn && message.status == DeliveryStatus.Failed) {
                        Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                            Text(stringResource(R.string.chat_not_sent), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.chat_try_again)) }
                        }
                    }
                }
            }
            menu()
        }
    }
}

private fun clockTime(at: Instant): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date.from(at))

/** The quote of a replied-to message. [reply] is null while the original is older than the loaded history: a tap loads it. */
@Composable
private fun ReplyQuote(reply: Message?, author: String?, onClick: () -> Unit) {
    val text = if (reply != null) previewText(reply.preview) else stringResource(R.string.chat_original_message)
    val description = if (reply != null) stringResource(R.string.reply_to, author ?: stringResource(R.string.you)) + ": " + text else text
    Row(
        Modifier.clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(width = 3.dp, height = 32.dp).background(MaterialTheme.colorScheme.primary))
        Text(text, maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Ticks(status: DeliveryStatus) {
    // A failed message says "Not sent" in words below; it has no tick.
    if (status == DeliveryStatus.Failed) return
    val label = stringResource(
        when (status) {
            DeliveryStatus.Sending -> R.string.delivery_sending
            DeliveryStatus.Sent -> R.string.delivery_sent
            DeliveryStatus.Delivered -> R.string.delivery_delivered
            DeliveryStatus.Read -> R.string.delivery_read
            DeliveryStatus.Failed -> R.string.chat_not_sent
        },
    )
    val icon = if (status == DeliveryStatus.Sent || status == DeliveryStatus.Sending) R.drawable.ic_check else R.drawable.ic_done_all
    val tint = if (status == DeliveryStatus.Read) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(14.dp))
}

/** Message text with its web links tappable; a tap opens the system browser. */
@Composable
private fun LinkedText(text: String) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated: AnnotatedString = remember(text, linkColor) {
        val links = LinkFinder.find(text)
        if (links.isEmpty()) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                var at = 0
                for (link in links) {
                    append(text.substring(at, link.range.first))
                    withLink(LinkAnnotation.Url(link.url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                        append(text.substring(link.range))
                    }
                    at = link.range.last + 1
                }
                append(text.substring(at))
            }
        }
    }
    Text(annotated)
}

@Composable
private fun Body(message: Message, isOwn: Boolean, autoDownloads: (MediaKind) -> Boolean, onDownload: () -> Unit, media: MediaHost?) {
    when (val c = message.content) {
        is MessageContent.Text -> LinkedText(c.text)
        is MessageContent.Voice -> VoiceBubble(message, c.duration, isOwn, media, onDownload)
        is MessageContent.Photo -> PhotoBubble(
            message = message,
            title = stringResource(R.string.photo_with_size, dataSizeText(DataFormat.size(c.bytes))),
            isOwn = isOwn,
            wait = !autoDownloads(MediaKind.Photo),
            host = media,
            onDownload = onDownload,
        )
        is MessageContent.File -> FileBubble(
            message = message,
            name = c.name,
            title = stringResource(R.string.file_with_size, c.name, dataSizeText(DataFormat.size(c.bytes))),
            isOwn = isOwn,
            wait = !autoDownloads(MediaKind.File),
            host = media,
            onDownload = onDownload,
        )
        is MessageContent.Call, MessageContent.Deleted, MessageContent.Unsupported ->
            Text(previewText(message.preview), style = MaterialTheme.typography.bodyMedium)
        is MessageContent.System -> Unit
    }
    if (message.undecryptable) Text(stringResource(R.string.cannot_open_message), style = MaterialTheme.typography.labelSmall)
}
