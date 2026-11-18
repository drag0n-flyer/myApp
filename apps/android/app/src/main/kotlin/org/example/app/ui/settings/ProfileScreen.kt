package org.example.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.chats.Contact
import org.example.app.ui.Avatar
import org.example.app.ui.usernameText

/**
 * Name, username, "Sign out" and "Delete account". Both actions run on the server first and wipe this device only
 * when that worked: with no connection the screen says "No connection", nothing is removed, and the person can try
 * again. The [viewModel] runs them, so a rotation neither loses one nor leaves the rows disabled with nothing running.
 */
@Composable
fun ProfileScreen(
    me: Contact,
    viewModel: ProfileViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** What the account has stored as its name ("" when unknown): "Edit name" starts from it, never from the fallback "You". */
    storedName: String = me.displayName,
    /** Opens "My QR code"; null hides the row. */
    onMyQr: (() -> Unit)? = null,
) {
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingPhoto by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val expectReturn = org.example.app.ui.lock.LocalExpectReturn.current
    val hasPhoto = org.example.app.profile.LocalProfilePhotos.current?.collectAsStateWithLifecycle()?.value?.get(me.id)?.photo != null
    val library = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { context.contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_PICKED_BYTES) } } catch (e: Exception) { null }
            }
            if (bytes != null && bytes.isNotEmpty()) viewModel.setPhoto(bytes)
        }
    }
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) scope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
            }
            viewModel.setPhoto(bytes)
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy = state.busy != null

    SettingsPage(stringResource(R.string.settings_profile), onBack, modifier) {
        item {
            val editLabel = stringResource(R.string.profile_edit_name)
            ListItem(
                modifier = Modifier.clickable(enabled = !busy, onClickLabel = editLabel, role = Role.Button) { viewModel.openNameEditor() },
                leadingContent = { Avatar(me.displayName, size = 56.dp, colorKey = me.id) },
                headlineContent = { Text(me.displayName, style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text(usernameText(me.username)) },
                trailingContent = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
            )
            HorizontalDivider()
        }
        if (onMyQr != null) item {
            ListItem(
                modifier = Modifier.clickable(role = Role.Button, onClick = onMyQr),
                leadingContent = { Icon(painterResource(R.drawable.ic_qr_scan), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(stringResource(R.string.my_qr_title)) },
                trailingContent = { Icon(painterResource(R.drawable.ic_chevron), contentDescription = null) },
            )
            HorizontalDivider()
        }
        item {
            ListItem(
                modifier = Modifier.clickable(enabled = !busy, role = Role.Button) { choosingPhoto = true },
                headlineContent = { Text(stringResource(R.string.profile_change_photo)) },
            )
            if (hasPhoto) ListItem(
                modifier = Modifier.clickable(enabled = !busy, role = Role.Button) { viewModel.removePhoto() },
                headlineContent = { Text(stringResource(R.string.profile_remove_photo)) },
            )
            HorizontalDivider()
        }
        item {
            val working = stringResource(R.string.profile_signing_out)
            ListItem(
                modifier = Modifier.clickable(enabled = !busy, role = Role.Button) { confirm = SIGN_OUT },
                headlineContent = { Text(stringResource(R.string.profile_sign_out), color = MaterialTheme.colorScheme.error) },
                trailingContent = if (state.busy == ProfileAction.SigningOut) ({
                    CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = working }, strokeWidth = 2.dp)
                }) else null,
            )
        }
        item {
            val working = stringResource(R.string.profile_deleting)
            ListItem(
                modifier = Modifier.clickable(enabled = !busy, role = Role.Button) { confirm = DELETE },
                headlineContent = { Text(stringResource(R.string.profile_delete), color = MaterialTheme.colorScheme.error) },
                trailingContent = if (state.busy == ProfileAction.Deleting) ({
                    CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = working }, strokeWidth = 2.dp)
                }) else null,
            )
            FooterText(stringResource(R.string.profile_delete_footer))
        }
        state.error?.let { message -> item { FooterText(stringResource(message), error = true) } }
    }

    if (choosingPhoto) AlertDialog(
        onDismissRequest = { choosingPhoto = false },
        title = { Text(stringResource(R.string.profile_change_photo)) },
        text = {
            Column {
                TextButton(onClick = {
                    choosingPhoto = false; expectReturn()
                    library.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(stringResource(R.string.profile_photo_choose)) }
                TextButton(onClick = {
                    choosingPhoto = false; expectReturn()
                    try { camera.launch(null) } catch (e: android.content.ActivityNotFoundException) { }
                }) { Text(stringResource(R.string.profile_photo_take)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { choosingPhoto = false }) { Text(stringResource(R.string.cancel)) } },
    )

    if (state.editingName) EditNameDialog(storedName, onSave = viewModel::saveName, onCancel = viewModel::closeNameEditor)

    when (confirm) {
        SIGN_OUT -> AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(stringResource(R.string.profile_sign_out_confirm)) },
            confirmButton = { TextButton(onClick = { confirm = null; viewModel.signOut() }) { Text(stringResource(R.string.profile_sign_out)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
        DELETE -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.profile_delete_title)) },
            text = { Text(stringResource(R.string.profile_delete_confirm)) },
            confirmButton = { TextButton(onClick = { confirm = null; viewModel.deleteAccount() }) { Text(stringResource(R.string.profile_delete)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** At most [limit] bytes of [input], or null when there are more (nothing larger is ever held in memory). */
private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > limit) return null
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

private const val MAX_PICKED_BYTES = 32 * 1024 * 1024
private const val SIGN_OUT = "signOut"
private const val DELETE = "delete"
