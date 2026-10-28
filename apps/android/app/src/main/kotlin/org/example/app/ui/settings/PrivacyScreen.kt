package org.example.app.ui.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.example.app.R
import org.example.app.core.chats.ChatStore
import org.example.app.core.settings.IPHiding
import org.example.app.ui.keyString

@Composable
fun PrivacyScreen(settings: SettingsStore, chatStore: ChatStore, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val current by settings.settings.collectAsStateWithLifecycle()
    val blocked by chatStore.blockedIds.collectAsStateWithLifecycle()
    var showBlocked by rememberSaveable { mutableStateOf(false) }

    if (showBlocked) {
        BlockedList(chatStore, blocked, onBack = { showBlocked = false }, modifier)
        return
    }
    val disappearing = listOf(
        Choice<Double?>(null, stringResource(R.string.info_off)),
        Choice<Double?>(86_400.0, stringResource(R.string.disappear_1_day)),
        Choice<Double?>(604_800.0, stringResource(R.string.disappear_1_week)),
        Choice<Double?>(2_419_200.0, stringResource(R.string.disappear_4_weeks)),
    )
    SettingsPage(stringResource(R.string.settings_privacy), onBack, modifier) {
        item { SectionHeader(stringResource(R.string.privacy_ip_title)) }
        items(IPHiding.entries.toList(), key = { it.name }) { option ->
            RadioRow(keyString(option.titleKey), keyString(option.subtitleKey), current.ipHiding == option) {
                settings.update { it.copy(ipHiding = option) }
            }
        }
        item { SectionHeader(stringResource(R.string.privacy_find_title)) }
        item { SwitchRow(stringResource(R.string.privacy_find_username), current.findByUsername) { v -> settings.update { it.copy(findByUsername = v) } } }
        item { SwitchRow(stringResource(R.string.privacy_find_email), current.findByEmail) { v -> settings.update { it.copy(findByEmail = v) } } }
        item { SectionHeader(stringResource(R.string.privacy_messages)) }
        item { SwitchRow(stringResource(R.string.privacy_read_receipts), current.readReceipts) { v -> settings.update { it.copy(readReceipts = v) } } }
        item {
            ChoiceRow(stringResource(R.string.info_disappearing), disappearing, current.disappearingMessages) { v ->
                settings.update { it.copy(disappearingMessages = v) }
            }
        }
        item {
            ListItem(
                modifier = Modifier.clickable(role = Role.Button) { showBlocked = true },
                headlineContent = { Text(stringResource(R.string.privacy_blocked)) },
                trailingContent = { Text(blocked.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
    }
}

@Composable
private fun BlockedList(store: ChatStore, blocked: Set<String>, onBack: () -> Unit, modifier: Modifier) {
    BackHandler(onBack = onBack)
    SettingsPage(stringResource(R.string.privacy_blocked), onBack, modifier) {
        if (blocked.isEmpty()) item { FooterText(stringResource(R.string.privacy_blocked_empty)) }
        items(blocked.sorted(), key = { it }) { id ->
            ListItem(headlineContent = { Text(store.contact(id)?.displayName ?: id) })
        }
    }
}
