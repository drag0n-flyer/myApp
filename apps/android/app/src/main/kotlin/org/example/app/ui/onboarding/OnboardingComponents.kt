package org.example.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.account.AccountError

/** A scrolling page with a title, an optional message, content and a footer for the main button. */
@Composable
fun OnboardingPage(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (message != null) Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { footer() }
    }
}

/** The page's main button; shows a spinner and ignores taps while [busy]. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled && !busy) {
        if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
        Text(text)
    }
}

/** The plain-words text for an error, announced to TalkBack when it appears. */
@Composable
fun accountErrorText(error: AccountError): String = when (error) {
    AccountError.InviteRequired -> stringResource(R.string.err_invite_required)
    AccountError.InviteInvalid -> stringResource(R.string.err_invite_invalid)
    AccountError.UsernameTaken -> stringResource(R.string.err_username_taken)
    AccountError.InvalidUsername -> stringResource(R.string.err_invalid_username)
    AccountError.InvalidEmail -> stringResource(R.string.err_invalid_email)
    AccountError.CodeInvalid -> stringResource(R.string.err_code_invalid)
    AccountError.CodeExpired -> stringResource(R.string.err_code_expired)
    is AccountError.TooManyAttempts ->
        error.retryAfterSeconds?.let { stringResource(R.string.err_too_many_minutes, (it + 59) / 60) } ?: stringResource(R.string.err_too_many)
    AccountError.LinkExpired -> stringResource(R.string.err_link_expired)
    AccountError.WrongServer -> stringResource(R.string.err_wrong_server)
    AccountError.PasskeyUnavailable -> stringResource(R.string.err_passkey_unavailable)
    AccountError.BiometricsUnavailable -> stringResource(R.string.err_biometrics)
    AccountError.Network -> stringResource(R.string.err_network)
    AccountError.Server, AccountError.Cancelled -> stringResource(R.string.err_server)
}

@Composable
fun AccountErrorText(error: AccountError?, modifier: Modifier = Modifier) {
    if (error == null || error == AccountError.Cancelled) return
    Text(
        accountErrorText(error),
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}
