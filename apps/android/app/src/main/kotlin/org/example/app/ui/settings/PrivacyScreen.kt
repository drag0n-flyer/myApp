package org.example.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.example.app.R
import org.example.app.core.account.AccountError
import org.example.app.core.chats.ChatStore
import org.example.app.core.settings.IPHiding
import org.example.app.ui.keyString
import org.example.app.ui.lock.AppLock
import org.example.app.ui.lock.AuthResult
import org.example.app.ui.lock.DeviceAuth
import org.example.app.core.lock.AppLockPolicy
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch

/**
 * [saveFindByEmail] changes the setting, sends the choice to the server and, if it could not, puts the setting back and
 * throws; the screen then shows an error. "Find me by username" is not offered: the server has no such setting.
 */
@Composable
fun PrivacyScreen(
    settings: SettingsStore,
    chatStore: ChatStore,
    appLock: AppLock,
    deviceAuth: DeviceAuth,
    saveFindByEmail: suspend (Boolean) -> Unit,
    /** The server's current value, or null if it cannot be read now. */
    loadFindByEmail: suspend () -> Boolean?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Called once right after app lock is stored as on (notifications already showing must lose their content). */
    onLockSwitchedOn: () -> Unit = {},
) {
    val current by settings.settings.collectAsStateWithLifecycle()
    val blocked by chatStore.blockedIds.collectAsStateWithLifecycle()
    var showBlocked by rememberSaveable { mutableStateOf(false) }
    var emailError by rememberSaveable { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }
    // Show what the server has, unless the person already changed the switch while it loaded.
    LaunchedEffect(Unit) {
        val server = try { loadFindByEmail() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (server != null && !touched) settings.update { it.copy(findByEmail = server) }
    }

    if (showBlocked) {
        BlockedList(chatStore, blocked, onBack = { showBlocked = false }, modifier)
        return
    }
    SettingsPage(stringResource(R.string.settings_privacy), onBack, modifier) {
        item { AppLockRows(settings, appLock, deviceAuth, onLockSwitchedOn) }
        item { SectionHeader(stringResource(R.string.privacy_ip_title)) }
        items(IPHiding.entries.toList(), key = { it.name }) { option ->
            RadioRow(keyString(option.titleKey), keyString(option.subtitleKey), current.ipHiding == option) {
                settings.update { it.copy(ipHiding = option) }
            }
        }
        item { SectionHeader(stringResource(R.string.privacy_find_title)) }
        item {
            SwitchRow(stringResource(R.string.privacy_find_email), current.findByEmail) { value ->
                if (saving) return@SwitchRow // one save at a time
                touched = true
                saving = true
                emailError = null
                scope.launch {
                    try {
                        saveFindByEmail(value)
                        saving = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        saving = false
                        emailError = if (e is AccountError.Network) R.string.err_network else R.string.err_server
                    }
                }
            }
        }
        emailError?.let { error -> item { FooterText(stringResource(error), error = true) } }
        item { SectionHeader(stringResource(R.string.privacy_messages)) }
        item { SwitchRow(stringResource(R.string.privacy_read_receipts), current.readReceipts) { v -> settings.update { it.copy(readReceipts = v) } } }
        item { SwitchRow(stringResource(R.string.privacy_typing), current.typingIndicator) { v -> settings.update { it.copy(typingIndicator = v) } } }
        item { FooterText(stringResource(R.string.privacy_typing_footer)) }
        item { SwitchRow(stringResource(R.string.privacy_online), current.onlineAndLastSeen) { v -> settings.update { it.copy(onlineAndLastSeen = v) } } }
        item { FooterText(stringResource(R.string.privacy_online_footer)) }
        item {
            ListItem(
                modifier = Modifier.clickable(role = Role.Button) { showBlocked = true },
                headlineContent = { Text(stringResource(R.string.privacy_blocked)) },
                trailingContent = { Text(blocked.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
    }
}

/** A blocked person's name, else their username, else the ID. */
internal fun blockedName(store: ChatStore, id: String): String {
    val contact = store.contact(id)
    return contact?.displayName?.takeIf { it.isNotBlank() } ?: contact?.username?.takeIf { it.isNotBlank() } ?: id
}

@Composable
private fun BlockedList(store: ChatStore, blocked: Set<String>, onBack: () -> Unit, modifier: Modifier) {
    BackHandler(onBack = onBack)
    SettingsPage(stringResource(R.string.privacy_blocked), onBack, modifier) {
        if (blocked.isEmpty()) item { FooterText(stringResource(R.string.privacy_blocked_empty)) }
        items(blocked.sorted(), key = { it }) { id ->
            val name = blockedName(store, id)
            val label = stringResource(R.string.blocked_unblock_label, name)
            ListItem(
                headlineContent = { Text(name) },
                trailingContent = {
                    TextButton(onClick = { store.unblock(id) }, modifier = Modifier.semantics { contentDescription = label }) {
                        Text(stringResource(R.string.blocked_unblock))
                    }
                },
            )
        }
    }
}

/**
 * The app lock switch and "Lock after" (design 6.3). Turning it on or off needs one passed prompt of the phone's own
 * authentication. Without a screen lock the switch is disabled (and a lock that is already on can be turned off: there is
 * nothing to ask with, and the cover lets the person in, see [org.example.app.ui.lock.LockCover]). A prompt that comes back after
 * a sign-out (the settings were reset) changes nothing.
 */
@Composable
private fun AppLockRows(settings: SettingsStore, appLock: AppLock, auth: DeviceAuth, onSwitchedOn: () -> Unit) {
    val current by settings.settings.collectAsStateWithLifecycle()
    val secure by rememberRefreshedOnResume { auth.available }
    val title = stringResource(R.string.applock_title)
    val prompt = stringResource(R.string.applock_unlock_app, stringResource(R.string.app_name))
    val on = current.appLock
    val canChange = secure || on
    fun change(value: Boolean) {
        if (!canChange) return
        val generation = settings.generation
        fun apply() {
            if (settings.generation != generation) return
            settings.update(generation) { it.copy(appLock = value) }
            appLock.setEnabled(value)
            if (value) onSwitchedOn()
        }
        if (!secure) apply() // only reachable to turn it off
        else auth.authenticate(prompt) { result -> if (result == AuthResult.Success) apply() }
    }
    ListItem(
        modifier = Modifier
            .toggleable(value = on, enabled = canChange, role = Role.Switch, onValueChange = ::change),
        headlineContent = { Text(title) },
        supportingContent = {
            Text(if (secure) stringResource(R.string.applock_subtitle, stringResource(R.string.app_name)) else stringResource(R.string.applock_needs_screen_lock))
        },
        trailingContent = { Switch(checked = on, onCheckedChange = null, enabled = canChange) },
    )
    if (on) {
        ChoiceRow(
            stringResource(R.string.applock_after),
            AppLockPolicy.DELAYS.map { Choice(it, stringResource(delayLabel(it))) },
            AppLockPolicy.validDelay(current.lockAfterSeconds),
        ) { seconds -> settings.update { it.copy(lockAfterSeconds = seconds) } }
    }
}

private fun delayLabel(seconds: Int): Int = when (seconds) {
    0 -> R.string.applock_immediately
    60 -> R.string.applock_1_minute
    300 -> R.string.applock_5_minutes
    else -> R.string.applock_1_hour
}
