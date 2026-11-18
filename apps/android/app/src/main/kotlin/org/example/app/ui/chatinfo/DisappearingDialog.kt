package org.example.app.ui.chatinfo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.people.TimerChoice

/** The words for a timer choice. */
object DisappearingChoices {
    fun labelFor(seconds: Int): Int = when (TimerChoice.from(seconds)) {
        TimerChoice.Day -> R.string.timer_day
        TimerChoice.Week -> R.string.timer_week
        TimerChoice.Quarter -> R.string.timer_quarter
        else -> R.string.timer_off
    }
}

/** Off, 1 day, 7 days, 90 days, and what the setting means. */
@Composable
fun DisappearingDialog(current: Int, onDismiss: () -> Unit, onChoose: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.info_disappearing)) },
        text = {
            Column {
                for (choice in TimerChoice.entries) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = choice.seconds == current, role = Role.RadioButton) { onChoose(choice.seconds) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice.seconds == current, onClick = null)
                        Text(stringResource(DisappearingChoices.labelFor(choice.seconds)), Modifier.padding(start = 16.dp))
                    }
                }
                Text(stringResource(R.string.info_disappearing_explainer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
