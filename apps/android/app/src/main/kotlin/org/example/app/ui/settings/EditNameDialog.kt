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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
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

/** "Edit name" in a dialog window; the field has focus with the cursor at the end. */
@Composable
fun EditNameDialog(current: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    FormDialog(onDismiss = onCancel) { EditNameForm(current, onSave, onCancel, autoFocus = true) }
}

/**
 * The body: a one-line field starting at [current] (empty when no name is known), with the length as "12/40" under it
 * (code points of the trimmed text, in the error colour over 40). Save is on only while [DisplayNameRule] accepts the
 * text. The typed text survives rotation. [autoFocus] puts the cursor at the end when the form appears.
 */
@Composable
fun EditNameForm(current: String, onSave: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier, autoFocus: Boolean = false) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(current, TextRange(current.length))) }
    val trimmed = field.text.trim { Character.isWhitespace(it) || Character.isSpaceChar(it) }
    val length = trimmed.codePointCount(0, trimmed.length)
    val accepted = DisplayNameRule.clean(field.text)
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.profile_edit_name), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            field, { field = it },
            modifier = Modifier.focusRequester(focus),
            label = { Text(stringResource(R.string.ob_name)) },
            supportingText = {
                Text(
                    "$length/${DisplayNameRule.MAX}",
                    Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                    color = if (length > DisplayNameRule.MAX) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            isError = length > DisplayNameRule.MAX,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            TextButton(enabled = accepted != null, onClick = { accepted?.let(onSave) }) { Text(stringResource(R.string.folders_save)) }
        }
    }
}
