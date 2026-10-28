package org.example.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
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
    DevicePlatform.Other -> R.string.device_other
}

/** Loaded once when opened; no polling. "Link a device" opens the scan screen (task A8). */
@Composable
fun LinkedDevicesScreen(account: AccountService, onBack: () -> Unit, modifier: Modifier = Modifier, onLinkDevice: () -> Unit = {}, now: () -> Instant = Instant::now) {
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }
    val removing = devices.firstOrNull { it.id == removingId }
    var loadFailed by remember { mutableStateOf(false) }
    var removeFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(account, reload) {
        try { devices = account.devices(); loadFailed = false } catch (_: AccountError) { loadFailed = true }
    }

    SettingsPage(stringResource(R.string.settings_linked_devices), onBack, modifier) {
        if (loadFailed) item {
            ListItem(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                headlineContent = { Text(stringResource(R.string.devices_load_failed)) },
                trailingContent = { TextButton(onClick = { reload++ }) { Text(stringResource(R.string.try_again)) } },
            )
        }
        if (removeFailed) item {
            ListItem(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                headlineContent = { Text(stringResource(R.string.devices_remove_failed), color = MaterialTheme.colorScheme.error) },
            )
        }
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
                        IconButton(onClick = { removingId = device.id }) {
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
                modifier = Modifier.clickable(onClickLabel = label, role = Role.Button, onClick = onLinkDevice),
            )
        }
        item { FooterText(stringResource(R.string.devices_footer)) }
    }
    removing?.let { device ->
        AlertDialog(
            onDismissRequest = { removingId = null },
            title = { Text(stringResource(R.string.devices_remove_title)) },
            text = { Text(device.name) },
            confirmButton = {
                TextButton(onClick = {
                    removingId = null
                    scope.launch {
                        removeFailed = try { account.removeDevice(device.id); false } catch (_: AccountError) { true }
                        try { devices = account.devices() } catch (_: AccountError) { }
                    }
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { removingId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun relative(at: Instant, now: Instant): String =
    DateUtils.getRelativeTimeSpanString(at.toEpochMilli(), now.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS).toString()
