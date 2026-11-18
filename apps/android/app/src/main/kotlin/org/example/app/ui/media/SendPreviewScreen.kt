package org.example.app.ui.media

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.app.R
import org.example.app.core.chats.Picked
import org.example.app.core.settings.MediaKind
import org.example.app.media.VideoMeta

/**
 * What the person sees after choosing photos or videos, before anything is staged or uploaded: the items (swipe between
 * several), a caption field, Cancel and Send. [onSend] gets the caption as typed; [problem] is a refusal to show (too many,
 * a video too big) while the screen stays open. Nothing here reads the file beyond a sampled picture for the screen.
 */
@Composable
fun SendPreviewScreen(
    items: List<Picked>,
    onCancel: () -> Unit,
    onSend: (caption: String) -> Unit,
    problem: Int? = null,
) {
    var caption by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().imePadding()) {
            val pager = rememberPagerState(pageCount = { items.size })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                HorizontalPager(pager, Modifier.fillMaxSize()) { page -> PreviewPage(items[page]) }
                if (items.size > 1) {
                    Text(
                        stringResource(R.string.media_preview_position, pager.currentPage + 1, items.size),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            if (problem != null) {
                Text(stringResource(problem), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                placeholder = { Text(stringResource(R.string.media_add_caption)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White, unfocusedBorderColor = Color.White.copy(alpha = 0.6f),
                    focusedPlaceholderColor = Color.White.copy(alpha = 0.7f), unfocusedPlaceholderColor = Color.White.copy(alpha = 0.7f),
                    cursorColor = Color.White,
                ),
                maxLines = 4,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel), color = Color.White) }
                Button(onClick = { onSend(caption) }) { Text(stringResource(R.string.send)) }
            }
        }
    }
}

/** One item: its picture sampled to the screen, with a play mark on a video. */
@Composable
private fun PreviewPage(item: Picked) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val width = with(density) { 360.dp.roundToPx() }
    val height = with(density) { 560.dp.roundToPx() }
    val picture by produceState<Bitmap?>(null, item) {
        value = withContext(Dispatchers.IO) {
            val uri = if (item.path.startsWith("content:") || item.path.startsWith("file:")) Uri.parse(item.path) else Uri.fromFile(java.io.File(item.path))
            if (item.kind == MediaKind.Video) VideoMeta.frame(context, uri, width, height) else PhotoDecoder.decodeUriFit(context, uri, width, height)
        }
    }
    val isVideo = item.kind == MediaKind.Video
    val description = stringResource(if (isVideo) R.string.media_play_video else R.string.preview_photo)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val shown = picture
        if (shown != null) {
            Image(shown.asImageBitmap(), contentDescription = description, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Icon(painterResource(R.drawable.ic_image), contentDescription = description, tint = Color.White)
        }
        if (isVideo) {
            Icon(
                painterResource(R.drawable.ic_play), contentDescription = null, tint = Color.White,
                modifier = Modifier.size(64.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(12.dp),
            )
        }
    }
}
