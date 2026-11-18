package org.example.app.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.example.app.R

/**
 * The full-screen cover shown while the app is locked: the app name and an Unlock button, nothing else. While it is
 * shown nothing else is composed (the caller returns before the screens), so there is nothing behind it to read.
 * The system prompt opens by itself once per request ([AppLock.takePrompt]); cancelling it leaves the cover.
 */
@Composable
fun LockCover(lock: AppLock, auth: DeviceAuth, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.applock_unlock_app, stringResource(R.string.app_name))
    val requests by lock.promptRequests.collectAsState()
    val ask = {
        if (!auth.available) lock.unlock()
        else auth.authenticate(title) { result -> if (result == AuthResult.Success) lock.unlock() }
    }
    // A phone with no screen lock has nothing to ask with (it was removed after the lock was switched on): do not lock the person out.
    LaunchedEffect(requests) {
        if (lock.takePrompt() || !auth.available) ask()
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
            Button(onClick = { ask() }, modifier = Modifier.semantics { contentDescription = title }) {
                Text(stringResource(R.string.applock_unlock))
            }
        }
    }
}
