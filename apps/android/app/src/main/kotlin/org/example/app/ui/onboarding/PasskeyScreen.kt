package org.example.app.ui.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.account.AccountError

@Composable
fun PasskeyScreen(supportsPasskeys: Boolean, error: AccountError?, busy: Boolean, onCreate: () -> Unit, onUseEmailCode: () -> Unit) {
    if (supportsPasskeys) {
        OnboardingPage(
            stringResource(R.string.ob_passkey_title),
            message = stringResource(R.string.ob_passkey_message),
            footer = {
                PrimaryButton(stringResource(R.string.ob_passkey_create), onCreate, busy = busy)
                TextButton(onUseEmailCode, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy) {
                    Text(stringResource(R.string.ob_passkey_email_instead))
                }
            },
        ) {
            Icon(painterResource(R.drawable.ic_key), contentDescription = null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            AccountErrorText(error)
        }
    } else {
        OnboardingPage(
            stringResource(R.string.ob_passkey_title),
            message = stringResource(R.string.ob_passkey_unavailable),
            footer = { PrimaryButton(stringResource(R.string.ob_continue), onUseEmailCode, busy = busy) },
        ) {
            Icon(painterResource(R.drawable.ic_key), contentDescription = null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            AccountErrorText(error)
        }
    }
}
