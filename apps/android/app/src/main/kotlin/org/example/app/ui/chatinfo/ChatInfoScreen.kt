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
    onSearch: (name: String) -> Unit,
    onBlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chats by store.chats.collectAsStateWithLifecycle()
    val requests by store.requests.collectAsStateWithLifecycle()
    var revision by remember { mutableIntStateOf(0) }
    // Read again after the lists change or the verified flag is toggled.
    val chat = remember(chats, requests, revision) { store.chat(chatId) }
    val contact = chat?.let { store.contact(it.contactId) }
    var muted by rememberSaveable { mutableStateOf(false) }
    var showVerify by rememberSaveable { mutableStateOf(false) }
    var confirmReport by rememberSaveable { mutableStateOf(false) }
    var confirmBlock by rememberSaveable { mutableStateOf(false) }

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
        val verified = store.supportsSafetyNumbers && store.safetyNumber(chatId)?.isVerified == true
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar(contact.displayName, size = 84.dp)
                Text(contact.displayName, style = MaterialTheme.typography.titleLarge)
                Text(usernameText(contact.username), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onCall != null) TextButton(onClick = { onCall(false) }) { Text(stringResource(R.string.info_audio)) }
                    if (onCall != null) TextButton(onClick = { onCall(true) }) { Text(stringResource(R.string.info_video)) }
                    TextButton(onClick = { muted = !muted }) { Text(stringResource(if (muted) R.string.info_unmute else R.string.info_mute)) }
                    TextButton(onClick = { onSearch(contact.displayName) }) { Text(stringResource(R.string.info_search)) }
                }
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.info_media_files_links)) },
                trailingContent = { Text(chat.messages.count { it.content is MessageContent.Photo || it.content is MessageContent.File }.toString()) },
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
            if (store.supportsSafetyNumbers) {
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button) { showVerify = true },
                    leadingContent = { Icon(painterResource(R.drawable.ic_verified), contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.info_verify)) },
                )
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.info_disappearing)) },
                trailingContent = { Text(stringResource(R.string.info_off)) },
            )
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

        if (showVerify && store.supportsSafetyNumbers) {
            val number = store.safetyNumber(chatId)
            if (number != null) {
                ModalBottomSheet(onDismissRequest = { showVerify = false }) {
                    Column(Modifier.padding(16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        if (confirmReport) {
            AlertDialog(
                onDismissRequest = { confirmReport = false },
                title = { Text(stringResource(R.string.report_title)) },
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
