package org.example.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.example.app.R
import org.example.app.core.chats.VoiceGesture
import org.example.app.ui.media.CaptureState
import org.example.app.ui.media.LEVEL_BARS
import org.example.app.ui.media.MediaPlayerEngine
import org.example.app.ui.media.VoiceEngineFactory
import org.example.app.ui.media.VoicePreviewPlayer
import org.example.app.ui.media.voiceClock

/** What the composer needs to show and drive a voice message: the capture's state and the buttons' actions. */
class VoiceUi(
    val state: CaptureState = CaptureState.Idle,
    /** "Hold to record, release to send" shows while true. */
    val hint: Boolean = false,
    val onPress: (rtl: Boolean) -> Unit = {},
    val onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    val onLift: () -> Unit = {},
    /** The gesture ended any way but a finger lifting (cut off, second finger, button gone): keep or drop, never send. */
    val onInterrupt: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onSend: () -> Unit = {},
    val previewEngines: VoiceEngineFactory = MediaPlayerEngine.factory,
) {
    /** The finger is down and the note is being recorded: the hold bar shows and the microphone button stays put. */
    val holding: Boolean
        get() = (state as? CaptureState.Recording)?.let {
            !it.stopping && (it.phase == VoiceGesture.State.recording || it.phase == VoiceGesture.State.willCancel || it.phase == VoiceGesture.State.willLock)
        } == true

    companion object { val None = VoiceUi() }
}

/** The microphone: hold to record (the gesture lives here, so it survives the bar changing around it); a click, from TalkBack or a keyboard, starts a locked recording. */
@Composable
internal fun MicButton(enabled: Boolean, ui: VoiceUi, onActivate: () -> Unit, onAcross: (Float) -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val label = stringResource(R.string.record_voice)
    val current by rememberUpdatedState(ui)
    val activate by rememberUpdatedState(onActivate)
    val across by rememberUpdatedState(onAcross)
    val willLock = (ui.state as? CaptureState.Recording)?.phase == VoiceGesture.State.willLock
    Box(
        Modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
                if (enabled) onClick(label) { activate(); true }
            }
            .onKeyEvent { e ->
                val activateKey = e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Spacebar || e.key == Key.DirectionCenter
                if (enabled && activateKey && e.type == KeyEventType.KeyUp) { activate(); true } else activateKey
            }
            .focusable(enabled)
            .pointerInput(enabled, rtl) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    current.onPress(rtl)
                    var lifted = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUp()) { lifted = true; break }
                            if (!change.pressed) break
                            val moved = change.position - down.position
                            val dx = moved.x.toDp().value
                            val dy = moved.y.toDp().value
                            across.invoke(if (rtl) dx else -dx)
                            current.onDrag(dx, dy)
                            change.consume()
                        }
                    } finally {
                        across.invoke(0f)
                        // A finger that lifted ends the hold; anything else (cancelled block, lost pointer) must not send.
                        if (lifted) current.onLift() else current.onInterrupt()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_mic), contentDescription = null,
            tint = if (ui.holding) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ui.holding) {
            // The target of the slide up: a lock above the button, lit when the finger is far enough.
            Icon(
                painterResource(R.drawable.ic_lock), contentDescription = null,
                tint = if (willLock) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.offset(y = (-LOCK_GLYPH_OFFSET).dp).size(if (willLock) 28.dp else 20.dp),
            )
        }
    }
}

private const val LOCK_GLYPH_OFFSET = 72

/** While the finger is down: a red dot, the time, the level meter and "Slide to cancel", which fades as the finger moves. */
@Composable
internal fun HoldBar(recording: CaptureState.Recording, across: Float, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape))
        Text(voiceClock((recording.seconds * 1000).toInt()), style = MaterialTheme.typography.bodyMedium)
        LevelMeter(recording.levels, Modifier.weight(1f))
        Text(
            stringResource(R.string.voice_slide_to_cancel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha((1f - across / VoiceGesture.cancelDistance).coerceIn(0f, 1f)),
        )
    }
}

/** About 30 bars that scroll with the newest level on the trailing side. Decoration: the time is the readable part. */
@Composable
internal fun LevelMeter(levels: List<Float>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val padded = List(LEVEL_BARS - levels.size.coerceAtMost(LEVEL_BARS)) { 0f } + levels.takeLast(LEVEL_BARS)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.height(32.dp).clearAndSetSemantics {}) {
        val slot = size.width / LEVEL_BARS
        val bar = slot * 0.6f
        val minHeight = 2.dp.toPx()
        padded.forEachIndexed { i, level ->
            val h = maxOf(minHeight, level * size.height)
            val x = (if (rtl) LEVEL_BARS - 1 - i else i) * slot + (slot - bar) / 2
            drawRoundRect(color, Offset(x, (size.height - h) / 2), Size(bar, h), CornerRadius(bar / 2))
        }
    }
}

/** After the finger lifted on a locked recording: Delete, the time and meter, Stop and Send. */
@Composable
internal fun LockedBar(recording: CaptureState.Recording, ui: VoiceUi, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = ui.onDelete, enabled = !recording.stopping) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.voice_delete))
        }
        Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape))
        Text(voiceClock((recording.seconds * 1000).toInt()), style = MaterialTheme.typography.bodyMedium)
        LevelMeter(recording.levels, Modifier.weight(1f))
        Icon(
            painterResource(R.drawable.ic_lock), contentDescription = stringResource(R.string.voice_locked),
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp),
        )
        IconButton(onClick = ui.onStop, enabled = !recording.stopping) {
            Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.voice_stop))
        }
        IconButton(onClick = ui.onSend, enabled = !recording.stopping) {
            Icon(painterResource(R.drawable.ic_send), contentDescription = stringResource(R.string.voice_send))
        }
    }
}

/** The finished recording before it is sent: Delete, play or pause, a progress bar, the duration, and Send. */
@Composable
internal fun PreviewBar(preview: CaptureState.Preview, ui: VoiceUi, modifier: Modifier = Modifier) {
    val player = remember(preview.file, ui.previewEngines) { VoicePreviewPlayer(preview.file, ui.previewEngines) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player) { while (true) { delay(100); player.poll() } }
    val total = if (player.durationMs > 0) player.durationMs else (preview.seconds * 1000).toInt()
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = ui.onDelete, enabled = !preview.sending) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.voice_delete))
        }
        IconButton(onClick = player::toggle, enabled = !preview.sending) {
            Icon(
                painterResource(if (player.playing) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (player.playing) R.string.media_pause else R.string.voice_play),
            )
        }
        LinearProgressIndicator(
            progress = { if (total > 0) (player.positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.weight(1f),
        )
        Text(voiceClock(if (player.playing || player.positionMs > 0) player.positionMs else total), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = ui.onSend, enabled = !preview.sending) {
            Icon(painterResource(R.drawable.ic_send), contentDescription = stringResource(R.string.voice_send))
        }
    }
}

/** "Hold to record, release to send", for two seconds after a tap. */
@Composable
internal fun VoiceHint(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.voice_hold_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}
