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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import org.example.app.R
import org.example.app.core.calls.DataFormat
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.Message
import org.example.app.core.chats.SafeFileName
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.isVideo
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
    /** In a group, the sender's name when this message starts a run of theirs: shown with their tile above the bubble. */
    senderName: String? = null,
    /** A group message from a person I blocked: shown as one line with "Show" until the person asks to see it. */
    collapsed: Boolean = false,
    onShowCollapsed: () -> Unit = {},
    /** First and last bubble of a run by one sender: only the last has the tail corner, the first has more space above. */
    runFirst: Boolean = true,
    runLast: Boolean = true,
    /** Reply to this message (swipe toward the trailing side, or the "Reply" accessibility action); null where Reply does not apply. */
    onSwipeReply: (() -> Unit)? = null,
) {
    val content = message.content
    if (content is MessageContent.System) {
        Box(modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(previewText(message.preview), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    if (collapsed && !isOwn) {
        BlockedLine(modifier, onShowCollapsed)
        return
    }
    val own = MaterialTheme.colorScheme.primaryContainer
    val other = MaterialTheme.colorScheme.surfaceContainerHigh
    val base = if (isOwn) own else other
    val fill by animateColorAsState(if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else base, label = "highlight")
    val tail = if (runLast) 4.dp else 18.dp
    val shape = RoundedCornerShape(18.dp, 18.dp, if (isOwn) tail else 18.dp, if (isOwn) 18.dp else tail)
    val optionsLabel = stringResource(R.string.chat_message_options)
    val press = if (onLongPress == null) Modifier else Modifier
        .pointerInput(onLongPress) { detectTapGestures(onLongPress = { onLongPress() }) }
        .semantics { onLongClick(optionsLabel) { onLongPress(); true } }
    val replyLabel = stringResource(R.string.chat_action_reply)
    val replyAction = if (onSwipeReply == null) Modifier else Modifier.semantics { customActions = listOf(CustomAccessibilityAction(replyLabel) { onSwipeReply(); true }) }
    SwipeToReply(onSwipeReply, modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().then(replyAction).padding(start = 12.dp, end = 12.dp, top = if (runFirst) 4.dp else 0.dp, bottom = if (message.reactions.isNotEmpty()) 12.dp else 0.dp), horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start) {
        Column {
        if (senderName != null && !isOwn) SenderLine(senderName, message.senderId)
        Box {
            // The flash after a jump is a fill and an outline, so it shows on both the own and the other bubble colour.
            val outline = if (highlighted) BorderStroke(3.dp, MaterialTheme.colorScheme.tertiary) else null
            Surface(shape = shape, color = fill, border = outline, modifier = Modifier.widthIn(max = 300.dp).then(press)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (message.replyToId != null) ReplyQuote(replyTo, replyAuthor, onQuoteClick)
                    Body(message, autoDownloads, onDownload, media)
                    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (message.editedAt != null) Text(stringResource(R.string.edited), style = MaterialTheme.typography.labelSmall)
                        // A photo, file or voice message that is still on its way says so in words, with the clock mark after it.
                        if (isOwn && message.status == DeliveryStatus.Sending && content.isAttachment()) {
                            Text(stringResource(R.string.media_sending), style = MaterialTheme.typography.labelSmall)
                        }
                        if (message.expiresAt != null) {
                            Icon(painterResource(R.drawable.ic_timer), contentDescription = stringResource(R.string.timer_glyph), modifier = Modifier.size(12.dp))
                        }
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
            // Reactions sit on the lower edge of the bubble as a small chip, like a sticker.
            if (message.reactions.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 2.dp,
                    modifier = Modifier.align(if (isOwn) Alignment.BottomEnd else Alignment.BottomStart).offset(x = if (isOwn) (-8).dp else 8.dp, y = 12.dp),
                ) {
                    Text(message.reactions.joinToString(" "), Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
            menu()
        }
        }
    }
    }
}

/** The name and tile of a group member above the first bubble of their run. One element for a screen reader. */
@Composable
private fun SenderLine(name: String, accountId: String) {
    val label = stringResource(R.string.message_info_sender_label, name)
    Row(
        Modifier.padding(start = 4.dp, bottom = 2.dp).semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        org.example.app.ui.Avatar(name, size = 20.dp, colorKey = accountId)
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** "Message from a blocked contact" with a "Show" button; nothing of the message is in the tree until it is shown. */
@Composable
private fun BlockedLine(modifier: Modifier, onShow: () -> Unit) {
    val showLabel = stringResource(R.string.group_blocked_show_label)
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.Start) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.group_blocked_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onShow, modifier = Modifier.semantics { contentDescription = showLabel }) { Text(stringResource(R.string.group_blocked_show)) }
            }
        }
    }
}

private fun MessageContent.isAttachment() = this is MessageContent.Photo || this is MessageContent.File || this is MessageContent.Voice

private fun clockTime(at: Instant): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date.from(at))

/** The quote of a replied-to message. [reply] is null while the original is older than the loaded history: a tap loads it. */
@Composable
private fun ReplyQuote(reply: Message?, author: String?, onClick: () -> Unit) {
    val text = if (reply != null) previewText(reply.preview) else stringResource(R.string.chat_original_message)
    val description = if (reply != null) stringResource(R.string.reply_to, author ?: stringResource(R.string.you)) + ": " + text else text
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .height(IntrinsicSize.Min).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        Text(text, Modifier.padding(end = 8.dp, top = 4.dp, bottom = 4.dp), maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    // The clock while it is on its way, one tick when sent, two when delivered or read.
    val icon = when (status) {
        DeliveryStatus.Sending -> R.drawable.ic_schedule
        DeliveryStatus.Sent -> R.drawable.ic_check
        else -> R.drawable.ic_done_all
    }
    val tint = if (status == DeliveryStatus.Read) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(14.dp))
}

/** The caption under a photo, video or file: formatted like message text. */
@Composable
private fun Caption(caption: String?) {
    if (!caption.isNullOrEmpty()) FormattedText(caption)
}

@Composable
private fun Body(message: Message, autoDownloads: (MediaKind) -> Boolean, onDownload: () -> Unit, media: MediaHost?) {
    // A message of mine that has not uploaded has nothing to download; it shows as sending or failed with its own retry.
    val unsent = message.media == null && (message.status == DeliveryStatus.Sending || message.status == DeliveryStatus.Failed)
    when (val c = message.content) {
        is MessageContent.Text -> { FormattedText(c.text); c.preview?.let { LinkPreviewCardView(it) } }
        is MessageContent.Voice -> VoiceBubble(message, c.duration, media, onDownload)
        is MessageContent.Photo -> { PhotoBubble(
            message = message,
            title = stringResource(R.string.photo_with_size, dataSizeText(DataFormat.size(c.bytes))),
            wait = !autoDownloads(MediaKind.Photo) && !unsent,
            host = media,
            onDownload = onDownload,
        ); Caption(c.caption) }
        // The sender's name is never shown raw: what is shown is what open and share use.
        is MessageContent.File -> { if (c.isVideo) PhotoBubble(
            message = message,
            title = stringResource(R.string.video_with_length, dataSizeText(DataFormat.size(c.bytes))),
            wait = !autoDownloads(MediaKind.Video) && !unsent,
            host = media,
            onDownload = onDownload,
            video = true,
        ) else FileBubble(
            message = message,
            name = SafeFileName.of(c.name, "file"),
            title = stringResource(R.string.file_with_size, SafeFileName.of(c.name, "file"), dataSizeText(DataFormat.size(c.bytes))),
            wait = !autoDownloads(MediaKind.File) && !unsent,
            host = media,
            onDownload = onDownload,
        ); Caption(c.caption) }
        is MessageContent.Call, MessageContent.Deleted, MessageContent.Unsupported ->
            Text(previewText(message.preview), style = MaterialTheme.typography.bodyMedium)
        is MessageContent.System -> Unit
    }
    if (message.undecryptable) Text(stringResource(R.string.cannot_open_message), style = MaterialTheme.typography.labelSmall)
}
