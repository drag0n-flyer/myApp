package org.example.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.app.R

@Composable
fun WelcomeScreen(canCreateAccount: Boolean, onGetStarted: () -> Unit, onHaveAccount: () -> Unit) {
    val brand = stringResource(R.string.app_name)
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_lock), contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp),
            )
            Text(stringResource(R.string.ob_welcome_title, brand), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.ob_welcome_pitch), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Point(R.drawable.ic_signal, R.string.ob_point_data_title, R.string.ob_point_data_text)
                Point(R.drawable.ic_lock, R.string.ob_point_private_title, R.string.ob_point_private_text)
                Point(R.drawable.ic_server, R.string.ob_point_server_title, R.string.ob_point_server_text)
            }
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canCreateAccount) {
                PrimaryButton(stringResource(R.string.ob_get_started), onGetStarted)
                TextButton(onHaveAccount, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ob_have_account)) }
            } else {
                PrimaryButton(stringResource(R.string.ob_link_to_phone), onHaveAccount)
                Text(
                    stringResource(R.string.ob_create_on_phone_first, brand), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Point(icon: Int, title: Int, text: Int) {
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
