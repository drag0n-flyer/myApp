package org.example.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.example.app.R
import org.example.app.core.chats.ChatTimeline
import org.example.app.core.chats.Contact
import org.example.app.core.chats.DayLabel
import org.example.app.ui.Avatar

/** The centred chip above the first message of a day: "Today", "Yesterday" or the medium date. */
@Composable
internal fun DateChip(day: LocalDate, today: LocalDate = LocalDate.now(), modifier: Modifier = Modifier) {
    val label = when (ChatTimeline.dayLabel(day, today)) {
        DayLabel.Today -> stringResource(R.string.chat_today)
        DayLabel.Yesterday -> stringResource(R.string.chat_yesterday)
        DayLabel.Date -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(day)
    }
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(label, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The line above the first message that was unread when the chat opened. */
@Composable
internal fun UnreadLine(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f))
        Text(stringResource(R.string.chat_unread_messages), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.weight(1f))
    }
}

/** Accept, Block and Report in place of the composer on a message request. */
@Composable
internal fun RequestBar(onAccept: () -> Unit, onBlock: () -> Unit, onReport: () -> Unit, modifier: Modifier = Modifier) {
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onAccept, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.chat_request_accept)) }
        OutlinedButton(onClick = { confirm = "block" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.chat_request_block)) }
        OutlinedButton(onClick = { confirm = "report" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.chat_request_report)) }
    }
    when (confirm) {
        "block" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.block_title)) },
            confirmButton = { TextButton(onClick = { confirm = null; onBlock() }) { Text(stringResource(R.string.chat_request_block)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
        "report" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.report_title)) },
            confirmButton = { TextButton(onClick = { confirm = null; onReport() }) { Text(stringResource(R.string.chat_request_report)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Contacts a text can be forwarded to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForwardSheet(contacts: List<Contact>, onPick: (Contact) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(stringResource(R.string.chat_forward_to), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            LazyColumn {
                items(contacts, key = { it.id }) { contact ->
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Button) { onPick(contact) },
                        leadingContent = { Avatar(contact.displayName, size = 40.dp) },
                        headlineContent = { Text(contact.displayName) },
                    )
                }
            }
        }
    }
}

/** "Delete this message for everyone?" with Delete and Cancel. */
@Composable
internal fun DeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_delete_confirm)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.chat_action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
