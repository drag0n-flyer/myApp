package org.example.app.ui.media

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.app.R

/** Most the viewer decodes: a photo is zoomed in on, so it gets more than the screen needs, but never a 40-megapixel bitmap. */
private const val VIEWER_MAX_PIXELS = 12_000_000L
private const val MAX_ZOOM = 5f

/** What one page of the viewer holds once its file is open: a picture to zoom, or a video to play. */
private sealed interface ViewerPage {
    data object Loading : ViewerPage
    data class Photo(val bitmap: Bitmap) : ViewerPage
    data class Video(val file: File) : ViewerPage
    data object Failed : ViewerPage
}

/**
 * Every local photo and video of a chat, full screen, starting at [startId]: swipe to move on, pinch and double tap to zoom
 * a photo, the system's video controls on a video, Share, Save and Close; Back closes it too. A file is decrypted for as
 * long as its page is on screen and no longer. [messageIds] is in chat order, oldest first.
 */
@Composable
fun MediaViewerScreen(
    items: List<ViewerItem>,
    startId: String,
    opener: AttachmentOpener?,
    onDismiss: () -> Unit,
    onShare: (messageId: String) -> Unit,
    onSave: suspend (ViewerItem) -> Boolean,
) {
    if (items.isEmpty()) return
    val messageIds = items.map { it.id }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val start = messageIds.indexOf(startId).coerceAtLeast(0)
        val pager = rememberPagerState(initialPage = start, pageCount = { messageIds.size })
        val scope = rememberCoroutineScope()
        var saveResult by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(saveResult) {
            if (saveResult != null) {
                delay(2_000)
                saveResult = null
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { messageIds[it] }) { page ->
                ViewerPage(items[page], opener, active = pager.currentPage == page)
            }
            val current = messageIds[pager.currentPage.coerceIn(0, messageIds.lastIndex)]
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                val colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White)
                IconButton(onClick = onDismiss, colors = colors) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { scope.launch { saveResult = if (onSave(items[pager.currentPage.coerceIn(0, items.lastIndex)])) R.string.media_saved else R.string.media_save_failed } },
                        colors = colors,
                    ) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = stringResource(R.string.media_save))
                    }
                    IconButton(onClick = { onShare(current) }, colors = colors) {
                        Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.media_share))
                    }
                }
            }
            saveResult?.let {
                Text(
                    stringResource(it), color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ViewerPage(item: ViewerItem, opener: AttachmentOpener?, active: Boolean) {
    val messageId = item.id
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // The decrypted copy lives for as long as this page is composed; closing it deletes the plaintext.
        val page by produceState<ViewerPage>(ViewerPage.Loading, messageId, width, height, opener) {
            val opened = opener?.openOrNull(messageId)
            if (opened == null) {
                value = ViewerPage.Failed
                return@produceState
            }
            try {
                val loaded = withContext(Dispatchers.IO) {
                    if (item.video) {
                        ViewerPage.Video(opened.file)
                    } else {
                        PhotoDecoder.decodeFit(opened.file, width, height, oversample = 2, maxPixels = VIEWER_MAX_PIXELS)?.let { ViewerPage.Photo(it) } ?: ViewerPage.Failed
                    }
                }
                value = loaded
                // A video is played from its file: keep it until this page goes away. A photo is in memory: close at once.
                if (loaded is ViewerPage.Video) kotlinx.coroutines.awaitCancellation()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { opened.close() }
            }
        }
        when (val p = page) {
            is ViewerPage.Photo -> ZoomableImage(p.bitmap, stringResource(R.string.preview_photo))
            is ViewerPage.Video -> VideoPage(p.file, active)
            ViewerPage.Failed -> Text(
                stringResource(R.string.media_couldnt_download),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
            ViewerPage.Loading -> Unit
        }
    }
}

/** The platform's `VideoView` with the system's media controls; plays only while its page is the one on screen. */
@Composable
private fun VideoPage(file: File, active: Boolean) {
    val context = LocalContext.current
    val description = stringResource(R.string.media_play_video)
    var view by remember { mutableStateOf<VideoView?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            VideoView(ctx).also { v ->
                v.contentDescription = description
                v.setMediaController(MediaController(ctx).also { it.setAnchorView(v) })
                v.setOnPreparedListener { player: MediaPlayer -> player.isLooping = false }
                v.setVideoPath(file.path)
                view = v
            }
        },
    )
    LaunchedEffect(view, active) {
        val v = view ?: return@LaunchedEffect
        if (active) v.start() else v.pause()
    }
    DisposableEffect(Unit) { onDispose { view?.stopPlayback() } }
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
                    // One finger on an unzoomed photo is left alone, so the pager can swipe; two fingers, or any drag while
                    // zoomed in, move the picture.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            if (event.changes.size > 1 || scale > 1f) {
                                scale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                offset = clamp(offset + event.calculatePan() * scale, scale)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
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
