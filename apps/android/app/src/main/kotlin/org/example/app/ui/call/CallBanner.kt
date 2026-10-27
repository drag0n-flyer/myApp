package org.example.app.ui.call

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.calls.CallBanner
import org.example.app.ui.keyString

@DrawableRes
private fun icon(banner: CallBanner): Int = when (banner) {
    CallBanner.VideoLowered, CallBanner.AudioOnly -> R.drawable.ic_warning
    CallBanner.Reconnecting -> R.drawable.ic_sync
    CallBanner.ConnectionLost -> R.drawable.ic_call_end
}

/** Explains what the app did when the network changed. A polite live region, so TalkBack announces each change. */
@Composable
fun CallBannerView(banner: CallBanner, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(icon(banner)), contentDescription = null, tint = Color(0xFFFFB454))
            Text(keyString(banner.textKey), style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
    }
}
