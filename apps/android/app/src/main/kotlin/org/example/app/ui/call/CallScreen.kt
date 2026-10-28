package org.example.app.ui.call

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.example.app.R
import org.example.app.core.calls.CallEndReason
import org.example.app.core.calls.CallPhase
import org.example.app.core.calls.CallQuality
import org.example.app.core.calls.CallState
import org.example.app.core.calls.SimulatedNetwork
import org.example.app.ui.Avatar
import org.example.app.ui.DataText
import org.example.app.ui.dataUsedText
import org.example.app.ui.keyString
import org.example.app.ui.theme.BaselineDark
import org.example.app.ui.theme.AppTypography

/** The call screens ignore the system light or dark setting: always dark, like the iPhone ones. */
@Composable
fun CallTheme(onResolved: (ColorScheme) -> Unit = {}, content: @Composable () -> Unit) {
    LightStatusBarIcons()
    val colors = BaselineDark.copy(
        background = Color(0xFF0B0D1C),
        surface = Color(0xFF14172B),
        surfaceContainerHigh = Color(0xFF232844),
        onBackground = Color.White,
        onSurface = Color.White,
    )
    onResolved(colors)
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}

/** Dark backdrop, so the status bar icons must be light while a call screen shows; restores the previous look after. */
@Composable
internal fun LightStatusBarIcons() {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view) {
        val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()?.window
        if (window == null) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val previous = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = previous }
    }
}

/** Blocks touches from reaching whatever is under a call screen, and owns system Back (see [CallBackHandler]). */
internal fun Modifier.consumeTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
}

/** A call screen is not a back-stack entry: Back never reaches the chat under it. [onMinimise] is null until an in-call banner exists. */
@Composable
internal fun CallBackHandler(onMinimise: (() -> Unit)?) {
    BackHandler { onMinimise?.invoke() }
}

/** The one-second tick of the sample call clock. Tests inject their own. */
fun interface CallClock {
    suspend fun awaitNextSecond()
}

object SystemCallClock : CallClock {
    override suspend fun awaitNextSecond() = delay(1_000)
}

private val CallBackdrop = Brush.verticalGradient(listOf(Color(0xFF2A3560), Color(0xFF11142B), Color(0xFF070914)))

/**
 * In-app call screen: audio or video, network and data pills, banner, controls. The sample clock ticks once a second
 * only while the screen is on and visible (`ticking`), and stops when the call has ended.
 */
@Composable
fun CallScreen(
    viewModel: CallViewModel,
    modifier: Modifier = Modifier,
    ticking: Boolean = true,
    clock: CallClock = SystemCallClock,
    canFlipCamera: Boolean = false,
    onMinimise: (() -> Unit)? = null,
    onThemeResolved: (ColorScheme) -> Unit = {},
) {
    CallTheme(onResolved = onThemeResolved) {
        val call by viewModel.call.collectAsStateWithLifecycle()
        val current = call ?: return@CallTheme
        var showData by rememberSaveable { mutableStateOf(false) }
        CallBackHandler(onMinimise)

        if (ticking) {
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val live = !current.isEnded
            LaunchedEffect(lifecycle, live) {
                if (!live) return@LaunchedEffect
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        clock.awaitNextSecond()
                        viewModel.tick()
                    }
                }
            }
            // A finished call stays up for a moment so the last banner can be read.
            LaunchedEffect(current.isEnded) {
                if (current.isEnded) {
                    delay(2_000)
                    viewModel.dismissEnded()
                }
            }
        }

        Box(modifier.fillMaxSize().background(CallBackdrop).consumeTouches()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 14.dp, vertical = 12.dp)) {
                AnimatedVisibility(
                    visible = current.banner != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    // Keep the last banner while it animates out.
                    val banner = remember(current.banner) { current.banner }
                    banner?.let { CallBannerView(it, Modifier.padding(bottom = 8.dp)) }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (current.isVideoCall && current.isVideoOn) {
                        VideoStage()
                    } else {
                        AudioStage(current, viewModel.contactName(current), modeText(viewModel, current))
                    }
                }
                if (viewModel.showSimulator) SimulateMenu(viewModel, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
                CallControls(
                    current,
                    onSpeaker = viewModel::toggleSpeaker,
                    canFlip = canFlipCamera,
                    onFlip = {},
                    onMute = viewModel::toggleMute,
                    onVideo = viewModel::toggleVideo,
                    onData = { showData = true },
                    onEnd = viewModel::hangUp,
                )
            }
        }

        if (showData) {
            DataModeSheet(
                options = viewModel.sheetOptions(current),
                onSelect = { viewModel.setMode(it); showData = false },
                onDismiss = { showData = false },
            )
        }
    }
}

/** "Auto · Data saver" when automatic, otherwise the mode in use. */
@Composable
private fun modeText(viewModel: CallViewModel, call: CallState): String {
    val mode = keyString(org.example.app.core.calls.DataModeInfo.titleKey(call.effectiveMode))
    return if (viewModel.isAuto(call)) stringResource(R.string.call_mode_pill_auto, stringResource(R.string.mode_auto), mode) else mode
}

@Composable
private fun statusText(call: CallState): String = when (val phase = call.phase) {
    CallPhase.Connecting -> stringResource(R.string.call_connecting)
    CallPhase.Ringing -> stringResource(R.string.call_ringing)
    CallPhase.Incoming -> stringResource(R.string.call_incoming)
    CallPhase.Active, CallPhase.Reconnecting -> DataText.clock(call.elapsed)
    is CallPhase.Ended -> when (phase.reason) {
        // The banner already says "Call ended: connection lost".
        CallEndReason.HungUp, CallEndReason.ConnectionLost -> stringResource(R.string.call_ended)
        CallEndReason.Declined -> stringResource(R.string.call_declined)
        CallEndReason.Missed -> stringResource(R.string.call_no_answer)
        CallEndReason.AnsweredElsewhere -> stringResource(R.string.call_answered_elsewhere)
        CallEndReason.Failed -> stringResource(R.string.call_failed)
    }
}

@Composable
private fun AudioStage(call: CallState, name: String, modeText: String) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Avatar(name, size = 110.dp, modifier = Modifier.padding(bottom = 8.dp))
        Text(name, style = MaterialTheme.typography.headlineMedium, color = Color.White, textAlign = TextAlign.Center)
        Text(statusText(call), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f))
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val quality = keyString(call.quality.titleKey)
            val a11y = stringResource(R.string.call_network_a11y, quality)
            Pill(Modifier.semantics(mergeDescendants = true) { contentDescription = a11y }) {
                SignalBars(if (call.quality == CallQuality.Good) 3 else 1)
                Text(quality, style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
            Pill { Text(modeText, style = MaterialTheme.typography.labelLarge, color = Color.White) }
        }
        Pill { Text(dataUsedText(call.bytesUsed), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f)) }
    }
}

@Composable
private fun Pill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = CircleShape, color = Color.White.copy(alpha = 0.14f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

@Composable
private fun SignalBars(level: Int) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until 4) {
            Box(Modifier.width(3.dp).height((4 + i * 3).dp).background(if (i < level) Color(0xFF4CD964) else Color.White.copy(alpha = 0.3f), RoundedCornerShape(1.dp)))
        }
    }
}

/** Placeholder video until the WebRTC engine exists. */
@Composable
private fun VideoStage() {
    val remote = stringResource(R.string.call_remote_video)
    val self = stringResource(R.string.call_self_video)
    Box(Modifier.fillMaxSize()) {
        Icon(
            painterResource(R.drawable.ic_person), contentDescription = remote,
            tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(160.dp).align(Alignment.Center),
        )
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = 48.dp).size(width = 84.dp, height = 120.dp)
                .background(Brush.verticalGradient(listOf(Color.Gray, Color(0x663B6FD8))), RoundedCornerShape(16.dp))
                .semantics { contentDescription = self },
        )
    }
}

/** Sample build only: plays network conditions. */
@Composable
private fun SimulateMenu(viewModel: CallViewModel, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_sync), contentDescription = stringResource(R.string.call_simulate), tint = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable fun item(label: Int, action: () -> Unit) = DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { open = false; action() }, modifier = Modifier.semantics { role = Role.Button })
            item(R.string.call_simulate_good) { viewModel.simulate(SimulatedNetwork.Good) }
            item(R.string.call_simulate_weak) { viewModel.simulate(SimulatedNetwork.Weak) }
            item(R.string.call_simulate_very_weak) { viewModel.simulate(SimulatedNetwork.VeryWeak) }
            item(R.string.call_simulate_lost) { viewModel.simulate(SimulatedNetwork.Lost) }
            item(R.string.call_simulate_advance) { viewModel.advance(10.0) }
        }
    }
}
