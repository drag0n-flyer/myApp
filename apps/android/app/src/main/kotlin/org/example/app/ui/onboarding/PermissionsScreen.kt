package org.example.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.example.app.R

/** Says why each permission is wanted; the system prompts only start from "Continue" ([onRequest]). */
@Composable
fun PermissionsScreen(onRequest: () -> Unit, onDone: () -> Unit) {
    OnboardingPage(
        stringResource(R.string.ob_permissions_title),
        footer = { PrimaryButton(stringResource(R.string.ob_continue), onRequest) },
    ) {
        Reason(R.drawable.ic_bell, R.string.ob_perm_notifications, R.string.ob_perm_notifications_reason)
        Reason(R.drawable.ic_mic, R.string.ob_perm_microphone, R.string.ob_perm_microphone_reason)
    }
}

/** The permissions step: the screen above, then the system prompts for notifications and the microphone. */
@Composable
fun PermissionsStep(onDone: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onDone() }
    PermissionsScreen(
        onRequest = {
            val wanted = buildList {
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.RECORD_AUDIO)
            }
            launcher.launch(wanted.toTypedArray())
        },
        onDone = onDone,
    )
}

@Composable
private fun Reason(icon: Int, title: Int, text: Int) {
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
