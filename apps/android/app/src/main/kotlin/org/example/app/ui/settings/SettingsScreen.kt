package org.example.app.ui.settings

import androidx.compose.ui.semantics.Role
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.WindowInsets
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
import org.example.app.core.account.ConnectionStatus
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
    Licenses("settings/licenses", R.string.settings_licenses, R.drawable.ic_key);

    companion object {
        /** The mockup's groups: account rows, then preferences, then the about row. */
        val groups: List<List<SettingsPage>> = listOf(
            listOf(Server, LinkedDevices),
            listOf(Privacy, DataStorage, Notifications, ChatFolders, CallsAudio),
            listOf(Licenses),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    me: Contact,
    serverHost: String,
    linkedDevices: Int,
    onOpen: (SettingsPage) -> Unit,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }, scrollBehavior = scroll) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                val profile = stringResource(R.string.settings_profile)
                SettingsGroup {
                    ListItem(
                        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = profile, role = Role.Button, onClick = onOpenProfile),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Avatar(me.displayName, size = 64.dp, colorKey = me.id) },
                        headlineContent = { Text(me.displayName, style = MaterialTheme.typography.titleLarge) },
                        supportingContent = { Text(usernameText(me.username)) },
                        trailingContent = { Chevron() },
                    )
                }
            }
            for (group in SettingsPage.groups) item(key = group.first().name) {
                SettingsGroup {
                    group.forEachIndexed { index, page ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        val title = stringResource(page.title)
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable(onClickLabel = title, role = Role.Button) { onOpen(page) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { IconTile(page.icon) },
                            headlineContent = { Text(title) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    when (page) {
                                        SettingsPage.Server -> Text(serverHost, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        SettingsPage.LinkedDevices -> Text(linkedDevices.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        else -> {}
                                    }
                                    Chevron()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** A rounded card holding one group of rows, as in the approved mockup's grouped list. */
@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) { Column(content = content) }
}

/** The small tinted square that carries a row's icon. */
@Composable
internal fun IconTile(@DrawableRes icon: Int) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Chevron() {
    Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ServerScreen(host: String, onBack: () -> Unit, modifier: Modifier = Modifier, status: ConnectionStatus = ConnectionStatus.Connected) {
    SettingsPage(stringResource(R.string.settings_server), onBack, modifier) {
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.server_address)) }, trailingContent = { Text(host) })
        }
        item {
            val text = stringResource(
                when (status) {
                    ConnectionStatus.Connected -> R.string.server_connected
                    ConnectionStatus.Connecting -> R.string.status_connecting
                    ConnectionStatus.Offline -> R.string.status_offline
                },
            )
            ListItem(headlineContent = { Text(stringResource(R.string.server_status)) }, trailingContent = { Text(text) })
        }
        item { FooterText(stringResource(R.string.server_footer)) }
    }
}
