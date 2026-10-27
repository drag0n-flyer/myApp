package org.example.app.ui.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
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

@Composable
fun CreateAccountScreen(
    serverHost: String,
    usernameCheck: UsernameCheck?,
    error: AccountError?,
    busy: Boolean,
    onUsernameChanged: (String) -> Unit,
    onSubmit: (name: String, username: String, email: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    val ready = name.isNotBlank() && usernameCheck == UsernameCheck.Available && email.contains("@") && email.contains(".")
    OnboardingPage(
        stringResource(R.string.ob_account_title),
        footer = { PrimaryButton(stringResource(R.string.ob_continue), { onSubmit(name.trim(), username, email.trim()) }, enabled = ready, busy = busy) },
    ) {
        Text(stringResource(R.string.ob_joining, serverHost), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.ob_name)) },
            placeholder = { Text(stringResource(R.string.ob_name_hint)) },
            supportingText = { Text(stringResource(R.string.ob_name_help)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        OutlinedTextField(
            username, { username = it.lowercase().filter { c -> !c.isWhitespace() }; onUsernameChanged(username) },
            Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.ob_username)) },
            placeholder = { Text(stringResource(R.string.ob_username_hint)) },
            supportingText = usernameCheck?.let { { Text(usernameStatus(it)) } },
            isError = usernameCheck == UsernameCheck.Taken || usernameCheck == UsernameCheck.Invalid,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        )
        OutlinedTextField(
            email, { email = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.ob_email)) },
            placeholder = { Text(stringResource(R.string.ob_email_hint)) },
            supportingText = { Text(stringResource(R.string.ob_email_help)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        AccountErrorText(error)
    }
}

@Composable
private fun usernameStatus(check: UsernameCheck): String = when (check) {
    UsernameCheck.Available -> stringResource(R.string.ob_username_available)
    UsernameCheck.Taken -> stringResource(R.string.ob_username_taken)
    UsernameCheck.Invalid -> stringResource(R.string.ob_username_invalid)
    UsernameCheck.Unknown -> stringResource(R.string.ob_username_unknown)
}
