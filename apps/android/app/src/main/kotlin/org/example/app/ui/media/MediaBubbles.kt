package org.example.app.ui.media

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.example.app.R
import org.example.app.core.chats.MediaBubbleState
import org.example.app.core.chats.MediaState
import org.example.app.core.chats.Message

/**
 * What a chat's media bubbles need from outside the bubble. [opener] decrypts a download so it can be decoded or played;
 * without one (sample data, signed out) a downloaded photo shows the neutral placeholder.
 */
class MediaHost(
    val opener: AttachmentOpener?,
    val photos: PhotoCache,
    val onOpenPhoto: (messageId: String) -> Unit = {},
    val fileExists: (String) -> Boolean = { File(it).exists() },
)

/**
 * The bubble's state from the message's media state. Whether a downloaded file is still there is asked off the main
 * thread; until the answer arrives the file is assumed to be there, and a stale answer never replaces a newer one.
 */
@Composable
fun rememberBubbleState(media: MediaState?, host: MediaHost?): MediaBubbleState {
    val local = media?.phase as? MediaState.Phase.Local
    // The other phases name no file, so there is nothing to look up.
    if (local == null) return MediaBubbleState(media) { true }
    val exists = host?.fileExists ?: { File(it).exists() }
    val state by produceState<MediaBubbleState>(MediaBubbleState.Ready(local.path), media) {
        value = withContext(Dispatchers.IO) { MediaBubbleState(media, exists) }
    }
    return state
}

private val BUBBLE_PHOTO_WIDTH = 276.dp
private val BUBBLE_PHOTO_HEIGHT = 320.dp

@Composable
fun PhotoBubble(
    message: Message,
    title: String,
    isOwn: Boolean,
    wait: Boolean,
    host: MediaHost?,
    onDownload: () -> Unit,
) {
    val state = rememberBubbleState(message.media, host)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state is MediaBubbleState.Ready) {
            PhotoImage(message, host)
        } else {
            MediaPlaceholder(state, nothingToDownload = isOwn && message.media == null, wait = wait, blurred = true, onDownload = onDownload)
        }
        Text(title, style = MaterialTheme.typography.labelMedium)
    }
}

/** The neutral box for a photo or file that is not shown: a download button, progress, "Couldn't download", or nothing yet. */
@Composable
fun MediaPlaceholder(state: MediaBubbleState, nothingToDownload: Boolean, wait: Boolean, blurred: Boolean, onDownload: () -> Unit) {
    val downloadingLabel = stringResource(R.string.downloading)
    val needsTap = state is MediaBubbleState.NotDownloaded && wait && !nothingToDownload
    Box(
        Modifier.fillMaxWidth().heightIn(min = 120.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        // Stand-in for the blurred preview; the real image is loaded only after the download.
        if (blurred && needsTap) {
            Box(Modifier.size(96.dp).blur(16.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp)))
        }
        when {
            state is MediaBubbleState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.media_couldnt_download), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDownload) { Text(stringResource(R.string.chat_try_again)) }
            }
            needsTap -> IconButton(onClick = onDownload) {
                Icon(painterResource(R.drawable.ic_download), contentDescription = stringResource(R.string.download))
            }
            state is MediaBubbleState.Downloading -> CircularProgressIndicator(
                progress = { state.fraction.toFloat() },
                modifier = Modifier.semantics { contentDescription = downloadingLabel }.size(32.dp),
            )
            nothingToDownload -> Icon(painterResource(R.drawable.ic_image), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private sealed interface PhotoLoad {
    data object Loading : PhotoLoad
    data class Loaded(val bitmap: Bitmap) : PhotoLoad
    data object Unreadable : PhotoLoad
}

/** A downloaded photo, decoded at the size it is shown, off the main thread, from the cache when it is there. */
@Composable
private fun PhotoImage(message: Message, host: MediaHost?) {
    val density = LocalDensity.current
    val boxWidth = with(density) { BUBBLE_PHOTO_WIDTH.roundToPx() }
    val boxHeight = with(density) { BUBBLE_PHOTO_HEIGHT.roundToPx() }
    val key = "${message.id}/${message.media?.attachmentId}/$boxWidth"
    // Restarts, and so cancels the older load, when the key changes: a stale picture never replaces a newer one.
    val load by produceState<PhotoLoad>(PhotoLoad.Loading, key, host) {
        val cached = host?.photos?.get(key)
        if (cached != null) {
            value = PhotoLoad.Loaded(cached)
            return@produceState
        }
        val opened = host?.opener?.openOrNull(message.id)
        if (opened == null) {
            value = PhotoLoad.Unreadable
            return@produceState
        }
        try {
            val bitmap = withContext(Dispatchers.IO) { PhotoDecoder.decodeFit(opened.file, boxWidth, boxHeight) }
            if (bitmap == null) {
                value = PhotoLoad.Unreadable
            } else {
                host.photos[key] = bitmap
                value = PhotoLoad.Loaded(bitmap)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { opened.close() }
        }
    }
    val description = stringResource(R.string.preview_photo)
    when (val l = load) {
        is PhotoLoad.Loaded -> Image(
            bitmap = l.bitmap.asImageBitmap(),
            contentDescription = description,
            modifier = Modifier
                .widthIn(max = BUBBLE_PHOTO_WIDTH)
                .heightIn(max = BUBBLE_PHOTO_HEIGHT)
                .aspectRatio(l.bitmap.width.toFloat() / l.bitmap.height.toFloat())
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { host?.onOpenPhoto?.invoke(message.id) },
        )
        PhotoLoad.Loading, PhotoLoad.Unreadable -> Box(
            Modifier.fillMaxWidth().heightIn(min = 120.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_image), contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
