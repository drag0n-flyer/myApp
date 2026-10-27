package org.example.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.account.AccountError
import org.example.app.core.account.LinkCode
import org.example.app.ui.qr.QrImage

/** New device: shows the QR code for the other phone to scan, then waits (no polling here; the service waits). */
@Composable
fun LinkDeviceScreen(
    serverHost: String,
    code: LinkCode?,
    expired: Boolean,
    error: AccountError?,
    canCreateAccount: Boolean,
    onNewCode: () -> Unit,
    onChangeServer: () -> Unit,
    onNoOldDevice: () -> Unit,
) {
    OnboardingPage(
        stringResource(R.string.link_title),
        message = stringResource(R.string.link_message, stringResource(R.string.app_name)),
        footer = {
            if (canCreateAccount) {
                TextButton(onNoOldDevice, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.link_no_old_device)) }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_server), contentDescription = null, Modifier.size(20.dp))
            Text(stringResource(R.string.link_server, serverHost), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onChangeServer) { Text(stringResource(R.string.link_change)) }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (code != null) QrImage(code.qrText, stringResource(R.string.link_qr_description))
            if (expired || error != null) {
                AccountErrorText(error ?: AccountError.LinkExpired)
                OutlinedButton(onNewCode) { Text(stringResource(R.string.link_new_code)) }
            } else if (code == null) {
                CircularProgressIndicator()
            } else {
                Text(stringResource(R.string.link_waiting), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
