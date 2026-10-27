package org.example.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.example.app.R

/** [canUseFullScreenIntent] is `NotificationManager.canUseFullScreenIntent()` on Android 14 and later, true before. */
@Composable
fun NotificationsScreen(
    delivery: NotificationDelivery,
    onDeliveryChange: (NotificationDelivery) -> Unit,
    canUseFullScreenIntent: Boolean,
    onOpenFullScreenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPage(stringResource(R.string.settings_notifications), onBack, modifier) {
        item { SectionHeader(stringResource(R.string.notif_delivery)) }
        item {
            RadioRow(stringResource(R.string.notif_google), stringResource(R.string.notif_google_subtitle), delivery == NotificationDelivery.Google) {
                onDeliveryChange(NotificationDelivery.Google)
            }
        }
        item {
            RadioRow(
                stringResource(R.string.notif_unifiedpush), stringResource(R.string.notif_unifiedpush_subtitle),
                delivery == NotificationDelivery.UnifiedPush,
            ) { onDeliveryChange(NotificationDelivery.UnifiedPush) }
        }
        if (!canUseFullScreenIntent) {
            item {
                ListItem(
                    modifier = Modifier.clickable(onClick = onOpenFullScreenSettings),
                    headlineContent = { Text(stringResource(R.string.notif_full_screen)) },
                    supportingContent = { Text(stringResource(R.string.notif_full_screen_subtitle)) },
                )
            }
        }
    }
}
