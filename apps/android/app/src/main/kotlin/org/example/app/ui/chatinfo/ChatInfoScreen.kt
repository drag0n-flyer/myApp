package org.example.app.ui.chatinfo

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.MessageContent
import org.example.app.ui.chat.ChatSearchRequests
import org.example.app.ui.usernameText
import org.example.app.ui.Avatar
import org.example.app.ui.keyString

/**
 * The only screen that shows how a chat is protected. Safety numbers (Verify, Verified) appear only while the
 * store can give real member keys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInfoScreen(
    store: ChatStore,
    chatId: String,
    onBack: () -> Unit,
    /** Null hides the call buttons (no live calls before E6). */
    onCall: ((video: Boolean) -> Unit)?,
    /** Not used any more: Search goes back to the chat through [ChatSearchRequests]. Kept so the call site compiles. */
    onSearch: (name: String) -> Unit,
    onBlocked: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens "Media and files" of this chat. */
    onOpenGallery: () -> Unit = {},
    /** A group's member menu: open that person's chat ([safetyNumber] false) or its info page ([safetyNumber] true). */
    onOpenMember: (accountId: String, safetyNumber: Boolean) -> Unit = { _, _ -> },
    /** After "Delete chat" on a group the person left. */
    onDeleted: () -> Unit = onBack,
) {
    // A group chat has its own info page (a group chat ID never changes for a chat).
    if (org.example.app.core.groups.ChatID.isGroup(chatId)) {
        GroupInfoScreen(store, chatId, onBack, onOpenGallery, onOpenMember, onDeleted, modifier)
        return
    }
    val chats by store.chats.collectAsStateWithLifecycle()
    val requests by store.requests.collectAsStateWithLifecycle()
    var revision by remember { mutableIntStateOf(0) }
    // Read again after the lists change or the verified flag is toggled.
    val chat = remember(chats, requests, revision) { store.chat(chatId) }
    val contact = chat?.contactId?.let { store.contact(it) }
    var showVerify by rememberSaveable { mutableStateOf(false) }
    var confirmReport by rememberSaveable { mutableStateOf(false) }
    var confirmBlock by rememberSaveable { mutableStateOf(false) }
    var pickTimer by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.info_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        if (chat == null || contact == null) return@Scaffold
        // Members that cannot be read: no number, no verified state, no Verify; chat info says why.
        val unreadable = store.supportsSafetyNumbers && store.membersUnreadable(chatId)
        val verified = store.supportsSafetyNumbers && !unreadable && store.safetyNumber(chatId)?.isVerified == true
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar(contact.displayName, size = 84.dp, colorKey = contact.id)
                Text(contact.displayName, style = MaterialTheme.typography.titleLarge)
                Text(usernameText(contact.username), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onCall != null) TextButton(onClick = { onCall(false) }) { Text(stringResource(R.string.info_audio)) }
                    if (onCall != null) TextButton(onClick = { onCall(true) }) { Text(stringResource(R.string.info_video)) }
                    // The chat underneath opens its search field when it is back on top.
                    TextButton(onClick = { ChatSearchRequests.request(chatId); onBack() }) { Text(stringResource(R.string.info_search)) }
                }
            }
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpenGallery),
                headlineContent = { Text(stringResource(R.string.info_media_and_files)) },
                trailingContent = { Text(chat.messages.count { it.content is MessageContent.Photo || it.content is MessageContent.File }.toString()) },
            )
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable(role = Role.Button) { pickTimer = true },
                leadingContent = { Icon(painterResource(R.drawable.ic_timer), contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.info_disappearing)) },
                trailingContent = { Text(stringResource(DisappearingChoices.labelFor(chat.timerSeconds))) },
            )
            HorizontalDivider()
            if (chat.modeMismatch) {
                // Sending is paused; this replaces the encryption line.
                ListItem(
                    leadingContent = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text(stringResource(R.string.info_mismatch), color = MaterialTheme.colorScheme.error) },
                )
            } else {
                val line = keyString(chat.encryption.infoKey)
                ListItem(
                    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = line },
                    leadingContent = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
                    headlineContent = { Text(line) },
                )
                if (verified) {
                    ListItem(
                        leadingContent = { Icon(painterResource(R.drawable.ic_verified), contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.info_verified)) },
                    )
                }
            }
            if (unreadable) {
                ListItem(
                    leadingContent = { Icon(painterResource(R.drawable.ic_warning), contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.info_members_unreadable)) },
                )
            } else if (store.supportsSafetyNumbers) {
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button) { showVerify = true },
                    leadingContent = { Icon(painterResource(R.drawable.ic_verified), contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.info_verify)) },
                )
            }
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable(role = Role.Button) { confirmReport = true },
                headlineContent = { Text(stringResource(R.string.info_report), color = MaterialTheme.colorScheme.error) },
            )
            ListItem(
                modifier = Modifier.clickable(role = Role.Button) { confirmBlock = true },
                headlineContent = { Text(stringResource(R.string.info_block, contact.displayName), color = MaterialTheme.colorScheme.error) },
            )
        }

        if (showVerify && store.supportsSafetyNumbers && !unreadable) {
            val number = store.safetyNumber(chatId)
            if (number != null) {
                ModalBottomSheet(onDismissRequest = { showVerify = false }) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.info_verify), style = MaterialTheme.typography.titleLarge)
                        number.groups.chunked(3).forEach { rowGroups ->
                            Text(rowGroups.joinToString("  "), style = MaterialTheme.typography.titleMedium)
                        }
                        Text(stringResource(R.string.verify_explainer, contact.displayName), style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth().toggleable(number.isVerified, role = Role.Switch) { store.setVerified(it, chatId); revision++ }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.verify_mark))
                            Switch(checked = number.isVerified, onCheckedChange = null)
                        }
                    }
                }
            }
        }
        if (pickTimer) {
            DisappearingDialog(chat.timerSeconds, onDismiss = { pickTimer = false }) { seconds ->
                pickTimer = false
                if (seconds != chat.timerSeconds) store.setTimer(chatId, seconds)
            }
        }
        if (confirmReport) {
            AlertDialog(
                onDismissRequest = { confirmReport = false },
                title = { Text(stringResource(R.string.report_title)) },
                text = { Text(stringResource(R.string.report_body)) },
                confirmButton = { TextButton(onClick = { confirmReport = false; store.report(chatId) }) { Text(stringResource(R.string.info_report)) } },
                dismissButton = { TextButton(onClick = { confirmReport = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        if (confirmBlock) {
            AlertDialog(
                onDismissRequest = { confirmBlock = false },
                title = { Text(stringResource(R.string.block_title)) },
                confirmButton = {
                    TextButton(onClick = { confirmBlock = false; store.block(contact.id); onBlocked() }) { Text(stringResource(R.string.info_block, contact.displayName)) }
                },
                dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

/**
 * The encryption line of a chat (or, when the server and the conversation disagree, the paused-sending line). The group
 * info page uses it too: chat info stays the only place that names an encryption type.
 */
@Composable
internal fun EncryptionInfoRows(chat: org.example.app.core.chats.Chat) {
    if (chat.modeMismatch) {
        ListItem(
            leadingContent = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            headlineContent = { Text(stringResource(R.string.info_mismatch), color = MaterialTheme.colorScheme.error) },
        )
    } else {
        val line = keyString(chat.encryption.infoKey)
        ListItem(
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = line },
            leadingContent = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
            headlineContent = { Text(line) },
        )
    }
}
