package org.example.app.ui.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import org.example.app.R
import org.example.app.core.account.AccountError

/** No old device: sign in with a synced passkey, or an email code. Old messages are not restored. */
@Composable
fun LostDeviceScreen(
    codeSent: Boolean,
    error: AccountError?,
    busy: Boolean,
    onPasskey: () -> Unit,
    onSendCode: (email: String) -> Unit,
    onSignIn: (email: String, code: String) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val emailOk = email.contains("@")
    OnboardingPage(
        stringResource(R.string.lost_title),
        message = stringResource(R.string.lost_message),
        footer = {
            if (codeSent) PrimaryButton(stringResource(R.string.lost_sign_in), { onSignIn(email.trim(), code) }, enabled = emailOk && code.length == CODE_LENGTH, busy = busy)
            else PrimaryButton(stringResource(R.string.lost_send_code), { onSendCode(email.trim()) }, enabled = emailOk, busy = busy)
        },
    ) {
        OutlinedButton(onPasskey, Modifier.fillMaxWidth(), enabled = !busy) { Text(stringResource(R.string.lost_passkey)) }
        Text(stringResource(R.string.lost_or_email), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            email, { email = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.ob_email)) },
            placeholder = { Text(stringResource(R.string.ob_email_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        if (codeSent) {
            OutlinedTextField(
                code, { code = sixDigits(it) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.ob_code)) },
                placeholder = { Text(stringResource(R.string.ob_code_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        }
        AccountErrorText(error)
    }
}
