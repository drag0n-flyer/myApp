package org.example.app.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.example.app.R

private val SwipeMax = 64.dp

/**
 * Drag the content toward the trailing side (mirrored in right-to-left) up to 64dp; a reply arrow fades in behind it.
 * Letting go past the threshold calls [onReply] once, with one haptic tick when the threshold is crossed; short of it the
 * content springs back. Only horizontal drags are claimed, so the list keeps scrolling. With a null [onReply] it does nothing.
 */
@Composable
fun SwipeToReply(onReply: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (onReply == null) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val max = with(density) { SwipeMax.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sign = if (rtl) -1f else 1f
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val reply by rememberUpdatedState(onReply)
    Box(modifier.pointerInput(max, sign) {
        var armed = false
        var pos = 0f
        detectHorizontalDragGestures(
            onDragStart = { armed = false; pos = offset.value },
            onDragEnd = {
                val fire = armed
                scope.launch { offset.animateTo(0f, spring()) }
                if (fire) reply()
            },
            onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } },
        ) { _, delta ->
            val next = (pos + delta * sign).coerceIn(0f, max)
            pos = next
            // The sign is folded in, so "toward trailing" is positive and dragging the other way never moves the bubble.
            scope.launch { offset.snapTo(next) }
            val now = next >= max
            if (now && !armed) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            armed = now
        }
    }) {
        Icon(
            painterResource(R.drawable.ic_reply), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(24.dp)
                .graphicsLayer { alpha = (offset.value / max).coerceIn(0f, 1f) },
        )
        Box(Modifier.offset { IntOffset((offset.value * sign).roundToInt(), 0) }) { content() }
    }
}
