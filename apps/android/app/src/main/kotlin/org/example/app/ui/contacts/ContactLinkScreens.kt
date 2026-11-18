package org.example.app.ui.contacts

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.app.R
import org.example.app.core.links.LinkProblem
import org.example.app.ui.Avatar
import org.example.app.ui.lock.LocalExpectReturn
import org.example.app.ui.qr.PermissionedQrScanner
import org.example.app.ui.qr.QrImage
import org.example.app.ui.usernameText

/** The message for a link that could not be used. */
@StringRes
fun linkProblemMessage(problem: LinkProblem): Int = when (problem) {
    LinkProblem.NotALink -> R.string.link_not_a_link
    LinkProblem.OtherServer -> R.string.link_other_server
    LinkProblem.OwnLink -> R.string.link_own
    LinkProblem.Expired -> R.string.link_expired
    LinkProblem.RateLimited -> R.string.link_rate_limited
    LinkProblem.Failed -> R.string.link_failed
    LinkProblem.TooManyLinks -> R.string.link_too_many
}

/** Opens the system share sheet with [text]. The person's lock allows the return from it. */
fun shareText(context: android.content.Context, text: String, expectReturn: () -> Unit) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    expectReturn()
    try {
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

/** The person's code for `https://<host>/u/<username>`, the username under it, and Share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyQrScreen(username: String, serverHost: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val link = "https://$serverHost/u/$username"
    val context = LocalContext.current
    val expectReturn = LocalExpectReturn.current
    val title = stringResource(R.string.my_qr_title)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QrImage(link, stringResource(R.string.my_qr_description), size = 260.dp)
            Text(usernameText(username), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.my_qr_footer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            val message = stringResource(R.string.invite_message, stringResource(R.string.app_name), link)
            Button(onClick = { shareText(context, message, expectReturn) }) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.my_qr_share), Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** The camera, until it reads a code: [onResult] gets its text (a link to open, handled like a pasted one). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanLinkScreen(onResult: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_link_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding -> Column(Modifier.padding(padding).fillMaxSize()) { PermissionedQrScanner(onResult) } }
}

/** A dialog window around [PasteLinkForm]. */
@Composable
fun PasteLinkDialog(onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    org.example.app.ui.settings.FormDialog(onDismiss = onDismiss) { PasteLinkForm(onOpen, onDismiss) }
}

/** A field to paste a link into; nothing is read from the clipboard by the app. A plain composable so tests can use it without a window. */
@Composable
fun PasteLinkForm(onOpen: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    // Not saved state: a pasted one-time link must not end up in a saved bundle.
    var text by remember { mutableStateOf("") }
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.add_paste_link), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.add_paste_link_hint)) },
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            TextButton(onClick = { onOpen(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.add_paste_link_open)) }
        }
    }
}

/** A link could not be used: the reason, one button. */
@Composable
fun LinkProblemDialog(problem: LinkProblem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(linkProblemMessage(problem))) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.link_problem_ok)) } },
    )
}

/**
 * Add-contact confirmation for a link: who it is (the display name when the server gave one) and Add. [onAdd] returns
 * whether the person was added; false shows a message and stays.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactLinkConfirmScreen(
    username: String,
    displayName: String?,
    onAdd: suspend () -> Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_add), Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val shown = displayName?.takeIf { it.isNotBlank() } ?: username
            Avatar(shown, size = 96.dp, colorKey = username)
            if (!displayName.isNullOrBlank()) Text(displayName, style = MaterialTheme.typography.titleLarge)
            Text(usernameText(username), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.link_confirm_message, shown), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            if (failed) Text(stringResource(R.string.link_confirm_unknown), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Button(
                onClick = {
                    busy = true; failed = false
                    scope.launch { if (!onAdd()) failed = true; busy = false }
                },
                enabled = !busy,
            ) { Text(stringResource(R.string.add_submit)) }
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.cancel)) }
        }
    }
}
