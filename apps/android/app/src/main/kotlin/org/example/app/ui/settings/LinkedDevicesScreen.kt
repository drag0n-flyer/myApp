package org.example.app.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import java.time.Instant
import kotlinx.coroutines.launch
import org.example.app.R
import org.example.app.core.account.AccountError
import org.example.app.core.account.AccountService
import org.example.app.core.account.DeviceInfo
import org.example.app.core.account.DevicePlatform

private fun platformName(platform: DevicePlatform): Int = when (platform) {
    DevicePlatform.IPhone -> R.string.device_iphone
    DevicePlatform.Mac -> R.string.device_mac
    DevicePlatform.Android -> R.string.device_android
}

/** Loaded once when opened; no polling. "Link a device" opens the scan screen (task A8). */
@Composable
fun LinkedDevicesScreen(account: AccountService, onBack: () -> Unit, modifier: Modifier = Modifier, onLinkDevice: () -> Unit = {}, now: () -> Instant = Instant::now) {
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var removing by remember { mutableStateOf<DeviceInfo?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(account) { devices = try { account.devices() } catch (_: AccountError) { emptyList() } }

    SettingsPage(stringResource(R.string.settings_linked_devices), onBack, modifier) {
        items(devices, key = { it.id }) { device ->
            val name = device.name
            ListItem(
                headlineContent = { Text(name) },
                supportingContent = {
                    Column {
                        Text(stringResource(platformName(device.platform)))
                        Text(
                            if (device.isThisDevice) stringResource(R.string.devices_active_now)
                            else stringResource(R.string.devices_last_active, relative(device.lastActive, now())),
                        )
                    }
                },
                trailingContent = if (device.isThisDevice) null else {
                    {
                        IconButton(onClick = { removing = device }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.devices_remove, name))
                        }
                    }
                },
            )
        }
        item {
            val label = stringResource(R.string.devices_link)
            ListItem(
                headlineContent = { Text(label) },
                modifier = Modifier.clickable(onClickLabel = label, onClick = onLinkDevice),
            )
        }
        item { FooterText(stringResource(R.string.devices_footer)) }
    }
    removing?.let { device ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.devices_remove_title)) },
            text = { Text(device.name) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch {
                        try { account.removeDevice(device.id) } catch (_: AccountError) { }
                        devices = try { account.devices() } catch (_: AccountError) { devices }
                    }
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun relative(at: Instant, now: Instant): String =
    DateUtils.getRelativeTimeSpanString(at.toEpochMilli(), now.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS).toString()
