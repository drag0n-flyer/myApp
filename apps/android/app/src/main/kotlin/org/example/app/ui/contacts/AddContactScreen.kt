package org.example.app.ui.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.app.R
import org.example.app.core.chats.ChatStore

/** Add a contact by username. Scanning a QR code arrives with the camera work in onboarding (A8). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(store: ChatStore, onBack: () -> Unit, onAdded: () -> Unit, modifier: Modifier = Modifier) {
    var username by rememberSaveable { mutableStateOf("") }
    var notFound by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clean = username.trim().trimStart('@').trim()
    fun submit() {
        if (clean.isEmpty()) return
        scope.launch {
            if (store.addContact(clean) != null) onAdded() else notFound = true
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_add)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; notFound = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.add_username)) },
                singleLine = true,
                isError = notFound,
                supportingText = if (notFound) ({ Text(stringResource(R.string.add_not_found)) }) else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Button(onClick = ::submit, enabled = clean.isNotEmpty(), modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.add_submit))
            }
        }
    }
}
