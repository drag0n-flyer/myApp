package org.example.app.ui.onboarding

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import org.example.app.R
import org.example.app.core.account.AccountError

@Composable
fun InviteCodeScreen(error: AccountError?, busy: Boolean, onSubmit: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val inviteError = error == AccountError.InviteInvalid || error == AccountError.InviteRequired
    OnboardingPage(
        stringResource(R.string.ob_invite_title),
        message = stringResource(R.string.ob_invite_message, stringResource(R.string.app_name)),
        footer = {
            if (!inviteError) AccountErrorText(error)
            PrimaryButton(stringResource(R.string.ob_continue), { onSubmit(code.trim().uppercase()) }, enabled = code.isNotBlank(), busy = busy)
        },
    ) {
        OutlinedTextField(
            code, { code = it.uppercase() }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.ob_invite_title)) },
            placeholder = { Text(stringResource(R.string.ob_invite_hint)) },
            isError = inviteError,
            supportingText = if (inviteError) { { Text(accountErrorText(error!!)) } } else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (code.isNotBlank() && !busy) onSubmit(code.trim().uppercase()) }),
        )
    }
}
