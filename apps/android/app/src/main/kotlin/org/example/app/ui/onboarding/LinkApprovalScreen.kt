package org.example.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.account.AccountError
import org.example.app.core.account.DevicePlatform
import org.example.app.core.account.LinkRequest
import org.example.app.ui.shortTime

@Composable
fun platformName(platform: DevicePlatform): String = stringResource(
    when (platform) {
        DevicePlatform.IPhone -> R.string.platform_iphone
        DevicePlatform.Mac -> R.string.platform_mac
        DevicePlatform.Android -> R.string.platform_android
        DevicePlatform.Other -> R.string.platform_other
    },
)

/** Approving phone: who is asking, the plain warning, then Approve or Don't approve. */
@Composable
fun LinkApprovalScreen(request: LinkRequest, error: AccountError?, busy: Boolean, onApprove: () -> Unit, onCancel: () -> Unit) {
    OnboardingPage(
        stringResource(R.string.link_approve_title),
        footer = {
            PrimaryButton(stringResource(R.string.link_approve), onApprove, busy = busy)
            TextButton(onCancel, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.link_dont_approve)) }
        },
    ) {
        Detail(R.string.link_device, request.deviceName)
        HorizontalDivider()
        Detail(R.string.link_type, platformName(request.platform))
        request.requestedAt?.let {
            HorizontalDivider()
            Detail(R.string.link_asked, shortTime(it))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.link_warning), style = MaterialTheme.typography.bodyMedium)
        }
        AccountErrorText(error)
    }
}

@Composable
private fun Detail(label: Int, value: String) {
    ListItem(headlineContent = { Text(value) }, overlineContent = { Text(stringResource(label)) })
}
