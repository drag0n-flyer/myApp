package org.example.app.ui.chatlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.ui.Avatar
import org.example.app.ui.previewText
import org.example.app.ui.shortTime

/** What a chat row can do. [setPinned] returns false when the limit of five is reached. */
class ChatRowActions(
    val setPinned: (chatId: String, pinned: Boolean) -> Boolean,
    val setMuted: (chatId: String, muted: Boolean) -> Unit,
    val setArchived: (chatId: String, archived: Boolean) -> Unit,
    val markRead: (chatId: String) -> Unit,
    val onPinRefused: () -> Unit,
)

/** One chat row: name, last message or draft, time, pin and muted icons, unread count. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRow(
    row: ChatRowModel, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null,
    /** Null: no selection state (one pane). True or false: the two-pane list, where the open chat's row is selected. */
    selected: Boolean? = null,
) {
    val preview = row.preview?.let { previewText(it) }.orEmpty()
    val clickable = if (onLongClick == null) {
        modifier.combinedClickable(role = Role.Button, onClick = onClick)
    } else {
        modifier.combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
    }
    ListItem(
        modifier = if (selected == null) clickable else clickable.semantics { this.selected = selected },
        colors = if (selected == true) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
        leadingContent = { Avatar(row.name, colorKey = row.colorKey) },
        headlineContent = { Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val draft = row.draft
            if (draft != null) {
                val label = stringResource(R.string.chat_draft_label)
                val red = MaterialTheme.colorScheme.error
                // The word Draft in the error colour, then the text, on one line.
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = red)) { append(label) }
                        append(" ")
                        append(draft.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty())
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (row.muted) {
                        Icon(
                            painterResource(R.drawable.ic_bell_off), contentDescription = stringResource(R.string.chat_muted),
                            modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (row.pinned) {
                        Icon(
                            painterResource(R.drawable.ic_pin), contentDescription = stringResource(R.string.chat_pinned),
                            modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    row.time?.let { Text(shortTime(it), style = MaterialTheme.typography.labelSmall) }
                }
                if (row.unreadCount > 0) {
                    UnreadBadge(row.unreadCount, muted = row.muted)
                }
            }
        },
    )
}

/** The unread count. A muted chat's count is neutral, not the accent colour. */
@Composable
fun UnreadBadge(count: Int, muted: Boolean, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.unread_badge, count)
    if (muted) {
        Badge(
            modifier.semantics { contentDescription = description },
            containerColor = MaterialTheme.colorScheme.outline, contentColor = MaterialTheme.colorScheme.surface,
        ) { Text(count.toString()) }
    } else {
        Badge(modifier.semantics { contentDescription = description }) { Text(count.toString()) }
    }
}

/**
 * A chat row with swipe actions and a long-press menu. Swiping from the end edge archives (unarchives in the archive),
 * from the start edge pins or unpins. The long-press menu has the same actions, plus Mute and Mark as read, and
 * screen readers get them as custom actions.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SwipeableChatRow(
    row: ChatRowModel,
    inArchive: Boolean,
    actions: ChatRowActions,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
) {
    val current by rememberUpdatedState(row)
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    fun togglePin() {
        if (!actions.setPinned(current.chatId, !current.pinned)) actions.onPinRefused()
    }
    fun toggleArchive() = actions.setArchived(current.chatId, !inArchive)

    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            when (target) {
                SwipeToDismissBoxValue.StartToEnd -> togglePin()
                SwipeToDismissBoxValue.EndToStart -> toggleArchive()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // The row stays: the chat list is rebuilt from the store, and a pinned row only moves.
            false
        },
    )

    val pinLabel = stringResource(if (row.pinned) R.string.chat_action_unpin else R.string.chat_action_pin)
    val archiveLabel = stringResource(if (inArchive) R.string.chat_action_unarchive else R.string.chat_action_archive)
    val muteLabel = stringResource(if (row.muted) R.string.call_ctrl_unmute else R.string.call_ctrl_mute)
    val readLabel = stringResource(R.string.chat_action_mark_read)

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = !inArchive,
        backgroundContent = {
            when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> SwipeBackground(
                    R.drawable.ic_pin, pinLabel, MaterialTheme.colorScheme.primaryContainer, Alignment.CenterStart,
                )
                SwipeToDismissBoxValue.EndToStart -> SwipeBackground(
                    if (inArchive) R.drawable.ic_unarchive else R.drawable.ic_archive, archiveLabel,
                    MaterialTheme.colorScheme.secondaryContainer, Alignment.CenterEnd,
                )
                SwipeToDismissBoxValue.Settled -> Box(Modifier.fillMaxSize())
            }
        },
    ) {
        Box {
            ChatRow(
                row, onClick, selected = selected,
                modifier = Modifier.semantics {
                    // No Pin in the archive: an archived chat is never pinned. Mark as read only with unread messages.
                    customActions = buildList {
                            if (!inArchive) add(CustomAccessibilityAction(pinLabel) { togglePin(); true })
                            add(CustomAccessibilityAction(muteLabel) { actions.setMuted(current.chatId, !current.muted); true })
                            add(CustomAccessibilityAction(archiveLabel) { toggleArchive(); true })
                            if (current.unreadCount > 0) add(CustomAccessibilityAction(readLabel) { actions.markRead(current.chatId); true })
                    }
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (!inArchive) DropdownMenuItem(text = { Text(pinLabel) }, onClick = { menuOpen = false; togglePin() })
                DropdownMenuItem(text = { Text(muteLabel) }, onClick = { menuOpen = false; actions.setMuted(current.chatId, !current.muted) })
                DropdownMenuItem(text = { Text(archiveLabel) }, onClick = { menuOpen = false; toggleArchive() })
                if (row.unreadCount > 0) {
                    DropdownMenuItem(text = { Text(readLabel) }, onClick = { menuOpen = false; actions.markRead(current.chatId) })
                }
            }
        }
    }
}

@Composable
private fun SwipeBackground(icon: Int, label: String, color: androidx.compose.ui.graphics.Color, align: Alignment) {
    Box(Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp), contentAlignment = align) {
        Icon(painterResource(icon), contentDescription = label)
    }
}
