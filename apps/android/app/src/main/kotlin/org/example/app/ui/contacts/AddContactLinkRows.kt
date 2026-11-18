package org.example.app.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.example.app.R

/** What the add-contact screen can offer besides a typed username. */
class AddContactLinks(
    val onScan: () -> Unit,
    val onPaste: () -> Unit,
    val onMyQr: () -> Unit,
    val onInviteWithLink: () -> Unit,
    /** True while a one-time link is being made. */
    val makingLink: Boolean,
    /** The message under the rows (a link that could not be made), or null. */
    @androidx.annotation.StringRes val message: Int? = null,
)

/** Scan, paste, show my code, invite with a link: rows under the username field. */
@Composable
fun AddContactLinkRows(links: AddContactLinks) {
    Column(Modifier.padding(top = 16.dp)) {
        HorizontalDivider()
        LinkRow(R.drawable.ic_qr_scan, R.string.add_scan, links.onScan)
        LinkRow(R.drawable.ic_link, R.string.add_paste_link, links.onPaste)
        LinkRow(R.drawable.ic_qr_scan, R.string.my_qr_title, links.onMyQr)
        val working = stringResource(R.string.add_make_link_busy)
        ListItem(
            modifier = Modifier.clickable(enabled = !links.makingLink, role = Role.Button, onClick = links.onInviteWithLink),
            leadingContent = { Icon(painterResource(R.drawable.ic_share), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(stringResource(R.string.add_invite_link), color = MaterialTheme.colorScheme.primary) },
            trailingContent = if (links.makingLink) ({
                CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = working }, strokeWidth = 2.dp)
            }) else null,
        )
        links.message?.let {
            Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun LinkRow(icon: Int, label: Int, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        leadingContent = { Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(stringResource(label), color = MaterialTheme.colorScheme.primary) },
    )
}
