package org.example.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import org.example.app.R
import org.example.app.push.PushTransport

/**
 * [transport] is how push reaches this phone now: read only, it follows what the phone has, not a choice made here.
 * [canUseFullScreenIntent] is `NotificationManager.canUseFullScreenIntent()` on Android 14 and later, true before.
 */
@Composable
fun NotificationsScreen(
    transport: PushTransport,
    canUseFullScreenIntent: Boolean,
    onOpenFullScreenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPage(stringResource(R.string.settings_notifications), onBack, modifier) {
        item { SectionHeader(stringResource(R.string.notif_delivery)) }
        item {
            val name = stringResource(
                when (transport) {
                    PushTransport.Fcm -> R.string.notif_google
                    PushTransport.UnifiedPush -> R.string.notif_unifiedpush
                    PushTransport.None -> R.string.notif_none
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.notif_delivered_through)) },
                trailingContent = { Text(name) },
            )
        }
        if (!canUseFullScreenIntent) {
            item {
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button, onClick = onOpenFullScreenSettings),
                    headlineContent = { Text(stringResource(R.string.notif_full_screen)) },
                    supportingContent = { Text(stringResource(R.string.notif_full_screen_subtitle)) },
                )
            }
        }
    }
}
