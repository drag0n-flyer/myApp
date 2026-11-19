package org.example.app.ui.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
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
    supportsPasskeys: Boolean = true,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val emailOk = email.contains("@")
    val focus = LocalFocusManager.current
    val emailError = error.isEmailFieldError()
    OnboardingPage(
        stringResource(R.string.lost_title),
        message = stringResource(R.string.lost_message),
        footer = {
            if (!emailError && !error.isCodeFieldError()) AccountErrorText(error)
            if (codeSent) PrimaryButton(stringResource(R.string.lost_sign_in), { onSignIn(email.trim(), code) }, enabled = emailOk && code.length == CODE_LENGTH, busy = busy)
            else PrimaryButton(stringResource(R.string.lost_send_code), { onSendCode(email.trim()) }, enabled = emailOk, busy = busy)
        },
    ) {
        if (supportsPasskeys) {
            OutlinedButton(onPasskey, Modifier.fillMaxWidth(), enabled = !busy) { Text(stringResource(R.string.lost_passkey)) }
            Text(stringResource(R.string.lost_or_email), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            email, { email = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.ob_email)) },
            placeholder = { Text(stringResource(R.string.ob_email_hint)) },
            isError = emailError,
            supportingText = if (emailError) { { Text(accountErrorText(error!!)) } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = if (codeSent) ImeAction.Next else ImeAction.Done),
            keyboardActions = KeyboardActions(
                onNext = { focus.moveFocus(FocusDirection.Down) },
                onDone = { if (emailOk && !busy) onSendCode(email.trim()) },
            ),
        )
        if (codeSent) {
            OutlinedTextField(
                code, { code = sixDigits(it) }, singleLine = true,
                label = { Text(stringResource(R.string.ob_code)) },
                placeholder = { Text(stringResource(R.string.ob_code_hint)) },
                isError = error.isCodeFieldError(),
                supportingText = if (error.isCodeFieldError()) { { Text(accountErrorText(error!!)) } } else null,
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.SmsOtpCode },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (emailOk && code.length == CODE_LENGTH && !busy) onSignIn(email.trim(), code) }),
            )
        }
    }
}
