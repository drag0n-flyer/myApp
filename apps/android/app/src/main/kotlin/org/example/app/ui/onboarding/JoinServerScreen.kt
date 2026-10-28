package org.example.app.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import org.example.app.R
import org.example.app.core.account.AccountError
import org.example.app.ui.qr.PermissionedQrScanner

private enum class Ask { None, Link, Address }

/**
 * Choose the server. The project's server comes first and is marked recommended; below it are the
 * other ways in. [onInviteText] gets pasted or scanned text and says whether it was an invite.
 * [scanner] shows the camera; it is composed only while the scan view is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinServerScreen(
    error: AccountError?,
    onDefaultServer: () -> Unit,
    onInviteText: (String) -> Boolean,
    onAddress: (String) -> Unit,
    scanner: @Composable (onResult: (String) -> Unit) -> Unit = { PermissionedQrScanner(it) },
) {
    var scanning by rememberSaveable { mutableStateOf(false) }
    var ask by rememberSaveable { mutableStateOf(Ask.None) }
    var notInvite by rememberSaveable { mutableStateOf(false) }

    if (scanning) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ob_scan_invite)) },
                actions = { TextButton({ scanning = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                scanner { text ->
                    scanning = false
                    notInvite = !onInviteText(text)
                }
            }
        }
        return
    }

    val brand = stringResource(R.string.app_name)
    OnboardingPage(stringResource(R.string.ob_join_title), message = stringResource(R.string.ob_join_message)) {
        DefaultServerCard(brand, onDefaultServer)
        Text(
            stringResource(R.string.ob_or_join_other), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
        )
        Option(R.drawable.ic_qr_scan, R.string.ob_scan_invite, R.string.ob_scan_invite_subtitle) { scanning = true }
        Option(R.drawable.ic_link, R.string.ob_paste_invite, R.string.ob_paste_invite_subtitle) { notInvite = false; ask = Ask.Link }
        Option(R.drawable.ic_edit, R.string.ob_enter_address, R.string.ob_enter_address_subtitle) { ask = Ask.Address }
        when (ask) {
            Ask.None -> {}
            Ask.Link -> TextEntry(
                hint = R.string.ob_invite_link_hint, confirm = R.string.ob_join,
                onDismiss = { ask = Ask.None },
            ) { ask = Ask.None; notInvite = !onInviteText(it.trim()) }
            Ask.Address -> TextEntry(
                hint = R.string.ob_server_address_hint, confirm = R.string.ob_continue,
                onDismiss = { ask = Ask.None },
            ) { ask = Ask.None; onAddress(it.trim()) }
        }
        if (notInvite) Text(stringResource(R.string.ob_not_invite_link), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        AccountErrorText(error)
    }
}

@Composable
private fun DefaultServerCard(brand: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_server), contentDescription = null, Modifier.size(32.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ob_server_name, brand), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.ob_run_by_team, brand), style = MaterialTheme.typography.bodySmall)
                }
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary) {
                    Text(
                        stringResource(R.string.ob_recommended), Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Icon(painterResource(R.drawable.ic_chevron), contentDescription = null)
            }
            Text(stringResource(R.string.ob_invite_during_beta), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Option(icon: Int, title: Int, subtitle: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(subtitle)) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron), contentDescription = null) },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}
