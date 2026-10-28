package org.example.app.ui.settings

import androidx.compose.ui.semantics.Role
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.chats.Contact
import org.example.app.ui.usernameText
import org.example.app.ui.Avatar

/** The pages the Settings tab opens. */
enum class SettingsPage(val route: String, @StringRes val title: Int, @DrawableRes val icon: Int) {
    Server("settings/server", R.string.settings_server, R.drawable.ic_settings),
    LinkedDevices("settings/devices", R.string.settings_linked_devices, R.drawable.ic_contacts),
    Privacy("settings/privacy", R.string.settings_privacy, R.drawable.ic_lock),
    DataStorage("settings/data", R.string.settings_data, R.drawable.ic_download),
    Notifications("settings/notifications", R.string.settings_notifications, R.drawable.ic_bell),
    ChatFolders("settings/folders", R.string.settings_folders, R.drawable.ic_inbox),
    CallsAudio("settings/audio", R.string.settings_calls_audio, R.drawable.ic_mic),
    Licenses("settings/licenses", R.string.settings_licenses, R.drawable.ic_key),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    me: Contact,
    serverHost: String,
    linkedDevices: Int,
    onOpen: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text(stringResource(R.string.tab_settings)) }, scrollBehavior = scroll) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                ListItem(
                    leadingContent = { Avatar(me.displayName, size = 56.dp) },
                    headlineContent = { Text(me.displayName, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(usernameText(me.username)) },
                )
                HorizontalDivider()
            }
            items(SettingsPage.entries.toList(), key = { it.name }) { page ->
                val title = stringResource(page.title)
                ListItem(
                    modifier = Modifier.fillMaxWidth().clickable(onClickLabel = title, role = Role.Button) { onOpen(page) },
                    leadingContent = { Icon(painterResource(page.icon), contentDescription = null) },
                    headlineContent = { Text(title) },
                    trailingContent = when (page) {
                        SettingsPage.Server -> ({ Text(serverHost, color = MaterialTheme.colorScheme.onSurfaceVariant) })
                        SettingsPage.LinkedDevices -> ({ Text(linkedDevices.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant) })
                        else -> null
                    },
                )
            }
        }
    }
}

@Composable
fun ServerScreen(host: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    SettingsPage(stringResource(R.string.settings_server), onBack, modifier) {
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.server_address)) }, trailingContent = { Text(host) })
        }
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.server_status)) }, trailingContent = { Text(stringResource(R.string.server_connected)) })
        }
        item { FooterText(stringResource(R.string.server_footer)) }
    }
}
