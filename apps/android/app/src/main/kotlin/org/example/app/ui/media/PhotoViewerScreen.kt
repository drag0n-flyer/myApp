package org.example.app.ui.media

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.example.app.R

/** Most the viewer decodes: a photo is zoomed in on, so it gets more than the screen needs, but never a 40-megapixel bitmap. */
private const val VIEWER_MAX_PIXELS = 12_000_000L
private const val MAX_ZOOM = 5f

/**
 * A downloaded photo full screen: pinch to zoom, drag when zoomed, double tap to zoom in and out, a share button and a
 * close button; Back closes it too. The photo is decrypted for as long as it takes to decode it and no longer.
 */
@Composable
fun PhotoViewerScreen(messageId: String, opener: AttachmentOpener?, onDismiss: () -> Unit, onShare: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            // Null while loading, and again if the photo cannot be read.
            var failed by remember(messageId) { mutableStateOf(false) }
            val bitmap by produceState<Bitmap?>(null, messageId, width, height, opener) {
                val opened = opener?.openOrNull(messageId)
                if (opened == null) {
                    failed = true
                    return@produceState
                }
                try {
                    val decoded = withContext(Dispatchers.IO) { PhotoDecoder.decodeFit(opened.file, width, height, oversample = 2, maxPixels = VIEWER_MAX_PIXELS) }
                    failed = decoded == null
                    value = decoded
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { opened.close() }
                }
            }
            val shown = bitmap
            if (shown != null) {
                ZoomableImage(shown, stringResource(R.string.preview_photo))
            } else if (failed) {
                Text(
                    stringResource(R.string.media_couldnt_download),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                val colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White)
                IconButton(onClick = onDismiss, colors = colors) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
                }
                IconButton(onClick = onShare, enabled = shown != null, colors = colors) {
                    Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.media_share))
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(bitmap: Bitmap, description: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        // Never pan further than the edge of the zoomed picture.
        fun clamp(o: Offset, s: Float) = Offset(
            o.x.coerceIn(-(s - 1f) * boxWidth / 2f, (s - 1f) * boxWidth / 2f),
            o.y.coerceIn(-(s - 1f) * boxHeight / 2f, (s - 1f) * boxHeight / 2f),
        )
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                        offset = clamp(offset + pan * scale, scale)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
                    })
                }
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
    }
}
