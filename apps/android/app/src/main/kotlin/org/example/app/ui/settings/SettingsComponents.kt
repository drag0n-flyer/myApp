package org.example.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.example.app.R

/** A sub-screen: top bar with Back, then a scrolling list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding -> LazyColumn(Modifier.padding(padding), content = content) }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)),
    )
}

@Composable
fun FooterText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A radio choice row; selected state is announced by TalkBack. */
@Composable
fun RadioRow(title: String, subtitle: String?, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
    )
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

data class Choice<T>(val value: T, val title: String, val subtitle: String? = null)

/** A row showing the current choice; tapping opens a dialog with the options. */
@Composable
fun <T> ChoiceRow(title: String, choices: List<Choice<T>>, selected: T, onSelect: (T) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val current = choices.firstOrNull { it.value == selected }?.title.orEmpty()
    ListItem(
        modifier = Modifier.clickable { open = true },
        headlineContent = { Text(title) },
        trailingContent = { Text(current, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                androidx.compose.foundation.layout.Column {
                    for (choice in choices) {
                        RadioRow(choice.title, choice.subtitle, choice.value == selected) { open = false; onSelect(choice.value) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
