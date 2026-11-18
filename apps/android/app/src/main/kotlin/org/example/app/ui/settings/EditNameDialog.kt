package org.example.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.profile.DisplayNameRule

/** "Edit name" in a dialog window. */
@Composable
fun EditNameDialog(current: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    FormDialog(onDismiss = onCancel) { EditNameForm(current, onSave, onCancel) }
}

/** The body: a one-line field starting at [current]; Save is on only while [DisplayNameRule] accepts the text. The typed text survives rotation. */
@Composable
fun EditNameForm(current: String, onSave: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf(current) }
    val accepted = DisplayNameRule.clean(text)
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.profile_edit_name), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            text, { text = it },
            label = { Text(stringResource(R.string.ob_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            TextButton(enabled = accepted != null, onClick = { accepted?.let(onSave) }) { Text(stringResource(R.string.folders_save)) }
        }
    }
}
