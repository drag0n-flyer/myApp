package org.example.app.ui.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.example.app.R
import org.example.app.core.account.AccountError
import org.example.app.core.account.UsernameCheck

/** What was typed on Create account; kept so "Start again" and Back return to the same details. */
data class SignUpDraft(val name: String = "", val username: String = "", val email: String = "")

@Composable
fun CreateAccountScreen(
    serverHost: String,
    usernameCheck: UsernameCheck?,
    error: AccountError?,
    busy: Boolean,
    onUsernameChanged: (String) -> Unit,
    onSubmit: (name: String, username: String, email: String) -> Unit,
    draft: SignUpDraft = SignUpDraft(),
) {
    var name by rememberSaveable { mutableStateOf(draft.name) }
    var username by rememberSaveable { mutableStateOf(draft.username) }
    var email by rememberSaveable { mutableStateOf(draft.email) }

    val ready = name.isNotBlank() && usernameCheck == UsernameCheck.Available && email.contains("@") && email.contains(".")
    val focus = LocalFocusManager.current
    val fieldError = error.isEmailFieldError() || error.isUsernameFieldError()
    OnboardingPage(
        stringResource(R.string.ob_account_title),
        footer = {
            if (!fieldError) AccountErrorText(error)
            PrimaryButton(stringResource(R.string.ob_continue), { onSubmit(name.trim(), username, email.trim()) }, enabled = ready, busy = busy)
        },
    ) {
        Text(stringResource(R.string.ob_joining, serverHost), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.ob_name)) },
            placeholder = { Text(stringResource(R.string.ob_name_hint)) },
            supportingText = { Text(stringResource(R.string.ob_name_help)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        )
        OutlinedTextField(
            username, { username = it.lowercase().filter { c -> !c.isWhitespace() }; onUsernameChanged(username) },
            Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.ob_username)) },
            placeholder = { Text(stringResource(R.string.ob_username_hint)) },
            supportingText = when {
                error.isUsernameFieldError() -> { { Text(accountErrorText(error!!)) } }
                usernameCheck != null -> { { Text(usernameStatus(usernameCheck)) } }
                else -> null
            },
            isError = error.isUsernameFieldError() || usernameCheck == UsernameCheck.Taken || usernameCheck == UsernameCheck.Invalid,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        )
        OutlinedTextField(
            email, { email = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.ob_email)) },
            placeholder = { Text(stringResource(R.string.ob_email_hint)) },
            supportingText = { Text(if (error.isEmailFieldError()) accountErrorText(error!!) else stringResource(R.string.ob_email_help)) },
            isError = error.isEmailFieldError(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (ready && !busy) onSubmit(name.trim(), username, email.trim()) }),
        )
    }
}

@Composable
private fun usernameStatus(check: UsernameCheck): String = when (check) {
    UsernameCheck.Available -> stringResource(R.string.ob_username_available)
    UsernameCheck.Taken -> stringResource(R.string.ob_username_taken)
    UsernameCheck.Invalid -> stringResource(R.string.ob_username_invalid)
    UsernameCheck.Unknown -> stringResource(R.string.ob_username_unknown)
}
