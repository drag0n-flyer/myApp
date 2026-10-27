package org.example.app.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.calls.DataSheetOption
import org.example.app.core.calls.ModePreference
import org.example.app.ui.keyString

/** "Data for this call" as a bottom sheet. The choice applies to this call only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataModeSheet(options: List<DataSheetOption>, onSelect: (ModePreference) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        DataModeSheetContent(options, onSelect)
    }
}

@Composable
fun DataModeSheetContent(options: List<DataSheetOption>, onSelect: (ModePreference) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Text(
            stringResource(R.string.call_data_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        for (option in options) OptionRow(option, onSelect)
        Text(
            stringResource(R.string.call_data_sheet_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun OptionRow(option: DataSheetOption, onSelect: (ModePreference) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = option.isSelected, enabled = option.isEnabled, role = Role.RadioButton) { onSelect(option.preference) }
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .alpha(if (option.isEnabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(keyString(option.titleKey), style = MaterialTheme.typography.bodyLarge)
            option.subtitleKey?.let {
                Text(keyString(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            option.noteKey?.let {
                Text(keyString(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        if (option.isSelected) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        } else option.estimate?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
