package org.example.app.ui.call

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.calls.CallState

internal val EndRed = Color(0xFFE5484D)

/** Speaker (Flip on video), Mute, Video, Data, End. */
@Composable
fun CallControls(
    call: CallState,
    onSpeaker: () -> Unit,
    onFlip: () -> Unit,
    onMute: () -> Unit,
    onVideo: () -> Unit,
    onData: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    /** Flip camera shows only once a camera exists; until then the video call has no such control. */
    canFlip: Boolean = false,
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (call.isVideoCall && call.isVideoOn) {
                if (canFlip) CallControl(stringResource(R.string.call_ctrl_flip), R.drawable.ic_sync, onClick = onFlip, modifier = Modifier.weight(1f))
            } else {
                CallControl(stringResource(R.string.call_ctrl_speaker), R.drawable.ic_volume_up, isOn = call.isSpeakerOn, onClick = onSpeaker, modifier = Modifier.weight(1f))
            }
            CallControl(
                stringResource(if (call.isMuted) R.string.call_ctrl_unmute else R.string.call_ctrl_mute),
                if (call.isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                isOn = call.isMuted, onClick = onMute, modifier = Modifier.weight(1f),
            )
            CallControl(
                stringResource(R.string.call_ctrl_video),
                if (call.isVideoOn) R.drawable.ic_videocam else R.drawable.ic_videocam_off,
                isOn = call.isVideoOn, onClick = onVideo, modifier = Modifier.weight(1f),
            )
            CallControl(stringResource(R.string.call_ctrl_data), R.drawable.ic_data_usage, onClick = onData, modifier = Modifier.weight(1f))
            CallControl(stringResource(R.string.call_ctrl_end), R.drawable.ic_call_end, tint = EndRed, onClick = onEnd, modifier = Modifier.weight(1f))
        }
    }
}

/** A round button with its label underneath. One TalkBack element, labelled with the visible text. */
@Composable
fun CallControl(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isOn: Boolean = false,
    tint: Color? = null,
) {
    val fill = tint ?: if (isOn) Color.White else Color.White.copy(alpha = 0.18f)
    val content = if (tint == null && isOn) Color.Black else Color.White
    Column(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                selected = isOn
                onClick { onClick(); true }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(52.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = content)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}
