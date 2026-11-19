package org.example.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.example.app.R
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.PollRules
import org.example.app.ui.Avatar

/** A poll inside a bubble: the question, each answer with its bar and count, and "View votes". A tap on an answer votes. */
@Composable
fun PollBody(poll: MessageContent.Poll, enabled: Boolean, nameOf: (String) -> String, onVote: (String) -> Unit) {
    var showVotes by rememberSaveable { mutableStateOf(false) }
    val total = poll.tally.voterCount
    Column(Modifier.widthIn(min = 240.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(poll.question, style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(if (poll.multi) R.string.poll_hint_multiple else R.string.poll_hint_single),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (option in poll.options) {
            val count = poll.tally.counts[option.id] ?: 0
            val selected = option.id in poll.tally.mine
            val fraction = if (total == 0) 0f else count.toFloat() / total
            val votes = pluralStringResource(R.plurals.poll_total_votes, count, count)
            val description = stringResource(R.string.poll_option_description, option.text, votes, (fraction * 100).roundToInt())
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .selectable(selected = selected, enabled = enabled, role = if (poll.multi) Role.Checkbox else Role.RadioButton) { onVote(option.id) }
                    .semantics { contentDescription = description },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // The row is the control; the mark only shows the state.
                    if (poll.multi) Checkbox(checked = selected, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(8.dp))
                    else RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(8.dp))
                    Text(option.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(count.toString(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                }
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (total == 0) stringResource(R.string.poll_no_votes_yet) else pluralStringResource(R.plurals.poll_total_votes, total, total),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showVotes = true }, enabled = total > 0) { Text(stringResource(R.string.poll_view_votes)) }
        }
    }
    if (showVotes) PollVotesSheet(poll, nameOf) { showVotes = false }
}

/** "View votes": each answer with its count and the people who chose it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PollVotesSheet(poll: MessageContent.Poll, nameOf: (String) -> String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.poll_votes_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(poll.question, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (option in poll.options) {
                val people = poll.tally.voters[option.id].orEmpty()
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).semantics(mergeDescendants = true) { heading() }, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(option.text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(pluralStringResource(R.plurals.poll_total_votes, people.size, people.size), style = MaterialTheme.typography.labelLarge)
                }
                if (people.isEmpty()) {
                    Text(stringResource(R.string.poll_voters_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                for (account in people) {
                    val name = nameOf(account)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(name, size = 32.dp, colorKey = account)
                        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * The sheet that makes a poll: a question, two to twelve answers (add and remove) and "Allow multiple answers".
 * [onSend] gets the typed text and returns false when it was refused (the sheet then stays).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewPollSheet(onDismiss: () -> Unit, onSend: (question: String, options: List<String>, multi: Boolean) -> Boolean) {
    var question by rememberSaveable { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var multi by rememberSaveable { mutableStateOf(false) }
    val ready = PollRules.build(question, options) != null
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.poll_new_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            OutlinedTextField(
                value = question, onValueChange = { question = it.take(PollRules.QUESTION_MAX) },
                label = { Text(stringResource(R.string.poll_question_label)) },
                modifier = Modifier.fillMaxWidth().testTag("poll_question"),
            )
            options.forEachIndexed { i, text ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = text, onValueChange = { options[i] = it.take(PollRules.OPTION_TEXT_MAX) },
                        label = { Text(stringResource(R.string.poll_option_label, i + 1)) },
                        singleLine = true, keyboardOptions = KeyboardOptions.Default,
                        modifier = Modifier.weight(1f).testTag("poll_option_$i"),
                    )
                    if (options.size > PollRules.MIN_OPTIONS) {
                        IconButton(onClick = { options.removeAt(i) }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.poll_remove_option, i + 1))
                        }
                    }
                }
            }
            if (options.size < PollRules.MAX_OPTIONS) {
                TextButton(onClick = { options.add("") }) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                    Text(stringResource(R.string.poll_add_option), Modifier.padding(start = 8.dp))
                }
            }
            val multiLabel = stringResource(R.string.poll_allow_multiple)
            Row(
                Modifier.fillMaxWidth().selectable(selected = multi, role = Role.Switch) { multi = !multi },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(multiLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = multi, onCheckedChange = null)
            }
            Button(onClick = { if (onSend(question, options.toList(), multi)) onDismiss() }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.poll_send))
            }
        }
    }
}
