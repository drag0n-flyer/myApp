package org.example.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.chats.MessageReceipt
import org.example.app.ui.Avatar
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date

/**
 * Message info for my message in a group: "Read by" and "Delivered to" with each member's name and time. A member who has
 * read it is listed under Read by only. [receipts] is null while they are being read. [nameOf] gives a member's name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageInfoSheet(receipts: List<MessageReceipt>?, nameOf: (String) -> String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.message_info), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (receipts != null) {
                val read = receipts.filter { it.readAt != null }.sortedBy { it.readAt }
                val delivered = receipts.filter { it.readAt == null && it.deliveredAt != null }.sortedBy { it.deliveredAt }
                Section(R.string.message_info_read_by, read.map { it.accountId to it.readAt!! }, nameOf)
                Section(R.string.message_info_delivered_to, delivered.map { it.accountId to it.deliveredAt!! }, nameOf)
            }
        }
    }
}

@Composable
private fun Section(title: Int, people: List<Pair<String, java.time.Instant>>, nameOf: (String) -> String) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
    if (people.isEmpty()) {
        Text(stringResource(R.string.message_info_nobody), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    for ((account, at) in people) {
        val name = nameOf(account)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(name, size = 32.dp, colorKey = account)
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
            Text(infoTime(at), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Time for today; date and time for any other day. */
internal fun infoTime(at: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val sameDay = at.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
    val format = if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT) else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return format.format(Date.from(at))
}
