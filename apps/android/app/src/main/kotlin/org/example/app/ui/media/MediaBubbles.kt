package org.example.app.ui.media

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.StateFlow
import org.example.app.core.chats.DeliveryStatus
import org.example.app.media.videoClock
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
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
    val onOpenFile: (messageId: String, name: String) -> Unit = { _, _ -> },
    val onShareFile: (messageId: String, name: String) -> Unit = { _, _ -> },
    val fileExists: (String) -> Boolean = { File(it).exists() },
    /** Plays voice messages; null leaves the play button without effect. */
    val voice: VoicePlayer? = null,
    /** The fraction uploaded of each outgoing attachment still uploading, by message id; null shows no progress. */
    val uploads: StateFlow<Map<String, Double>>? = null,
    /** Cancel on an outgoing attachment that is still uploading. */
    val onCancelUpload: (messageId: String) -> Unit = {},
)

private val NO_UPLOADS: StateFlow<Map<String, Double>> = MutableStateFlow(emptyMap())

/** True for an outgoing attachment that has not finished uploading: it has no stored copy yet and is still on its way. */
private fun Message.isUploading() = status == DeliveryStatus.Sending && media == null

/** The progress ring with Cancel (×) inside it. [fraction] is 0.0 to 1.0. */
@Composable
fun UploadRing(fraction: Double, onCancel: () -> Unit, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val sending = stringResource(R.string.media_sending)
    val cancel = stringResource(R.string.media_cancel_upload)
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { fraction.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.semantics { contentDescription = sending }.size(size),
        )
        IconButton(onClick = onCancel, modifier = Modifier.size(size)) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = cancel, modifier = Modifier.size(size / 2))
        }
    }
}

@Composable
private fun UploadBox(message: Message, host: MediaHost?) {
    val fractions by (host?.uploads ?: NO_UPLOADS).collectAsState()
    Box(
        Modifier.fillMaxWidth().heightIn(min = 120.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        UploadRing(fractions[message.id] ?: 0.0, onCancel = { host?.onCancelUpload?.invoke(message.id) })
    }
}

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
    wait: Boolean,
    host: MediaHost?,
    onDownload: () -> Unit,
    /** A video (file content): shown as a frame with a play mark; a photo whose bytes are not an image shows the broken-photo state. */
    video: Boolean = false,
) {
    val state = rememberBubbleState(message.media, host)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (message.isUploading()) {
            UploadBox(message, host)
        } else if (state is MediaBubbleState.Ready) {
            PhotoImage(message, host, video)
        } else {
            MediaPlaceholder(state, wait = wait, blurred = true, onDownload = onDownload)
        }
        Text(title, style = MaterialTheme.typography.labelMedium)
    }
}

private val NO_VOICE = MutableStateFlow(VoiceState())

/**
 * A voice message: play or pause, a progress bar, and the elapsed time while it plays (its length otherwise). Not yet
 * downloaded, the play button downloads it. A message that cannot be played says "Couldn't download" and offers to try again.
 */
@Composable
fun VoiceBubble(message: Message, seconds: Double, host: MediaHost?, onDownload: () -> Unit) {
    val state = rememberBubbleState(message.media, host)
    val voice = host?.voice
    val ui by (voice?.state ?: NO_VOICE).collectAsState()
    val mine = ui.messageId == message.id
    val playing = mine && ui.playing
    val failedHere = ui.failedId == message.id
    val totalMs = if (mine && ui.durationMs > 0) ui.durationMs else seconds.roundToInt() * 1000
    val positionMs = if (mine) ui.positionMs else 0
    val downloadingLabel = stringResource(R.string.downloading)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            state is MediaBubbleState.Downloading -> CircularProgressIndicator(
                progress = { state.fraction.toFloat() },
                modifier = Modifier.semantics { contentDescription = downloadingLabel }.size(32.dp),
            )
            state is MediaBubbleState.Failed || failedHere -> Column {
                Text(stringResource(R.string.media_couldnt_download), style = MaterialTheme.typography.bodyMedium)
                // A message that downloaded but would not play is tried again; one that did not download is fetched again.
                TextButton(onClick = { if (failedHere) voice?.toggle(message.id) else onDownload() }) { Text(stringResource(R.string.chat_try_again)) }
            }
            else -> IconButton(
                onClick = { if (state is MediaBubbleState.Ready) voice?.toggle(message.id) else onDownload() },
            ) {
                Icon(
                    painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(if (playing) R.string.media_pause else R.string.play_voice),
                )
            }
        }
        if (state !is MediaBubbleState.Failed && !failedHere && state !is MediaBubbleState.Downloading) {
            Column(Modifier.widthIn(min = 120.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = { if (totalMs > 0) (positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(voiceClock(if (mine) positionMs else totalMs), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** A file: its name and size, and what can be done with it. Opens with another app when downloaded; shares. */
@Composable
fun FileBubble(
    message: Message,
    name: String,
    title: String,
    wait: Boolean,
    host: MediaHost?,
    onDownload: () -> Unit,
) {
    val state = rememberBubbleState(message.media, host)
    val ready = state is MediaBubbleState.Ready
    val downloadingLabel = stringResource(R.string.downloading)
    val fractions by (host?.uploads ?: NO_UPLOADS).collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .weight(1f, fill = false)
                .then(if (ready) Modifier.clickable(role = Role.Button) { host?.onOpenFile?.invoke(message.id, name) } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(R.drawable.ic_file), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                if (state is MediaBubbleState.Failed) {
                    Text(stringResource(R.string.media_couldnt_download), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        when {
            message.isUploading() -> UploadRing(fractions[message.id] ?: 0.0, onCancel = { host?.onCancelUpload?.invoke(message.id) }, size = 32.dp)
            ready -> IconButton(onClick = { host?.onShareFile?.invoke(message.id, name) }) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.media_share))
            }
            state is MediaBubbleState.Failed -> TextButton(onClick = onDownload) { Text(stringResource(R.string.chat_try_again)) }
            state is MediaBubbleState.Downloading -> CircularProgressIndicator(
                progress = { state.fraction.toFloat() },
                modifier = Modifier.semantics { contentDescription = downloadingLabel }.size(24.dp),
            )
            state is MediaBubbleState.NotDownloaded && wait -> IconButton(onClick = onDownload) {
                Icon(painterResource(R.drawable.ic_download), contentDescription = stringResource(R.string.download))
            }
        }
    }
}

/** The neutral box for a photo or file that is not shown: a download button, progress, "Couldn't download", or nothing yet. */
@Composable
fun MediaPlaceholder(state: MediaBubbleState, wait: Boolean, blurred: Boolean, onDownload: () -> Unit) {
    val downloadingLabel = stringResource(R.string.downloading)
    val needsTap = state is MediaBubbleState.NotDownloaded && wait
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
        }
    }
}

private sealed interface PhotoLoad {
    data object Loading : PhotoLoad
    /** [videoMs] is the length of the video the picture is a frame of; null for a photo. */
    data class Loaded(val bitmap: Bitmap, val videoMs: Long? = null) : PhotoLoad
    data object Unreadable : PhotoLoad
}

/** A downloaded photo, decoded at the size it is shown, off the main thread, from the cache when it is there. */
@Composable
private fun PhotoImage(message: Message, host: MediaHost?, video: Boolean) {
    val density = LocalDensity.current
    val boxWidth = with(density) { BUBBLE_PHOTO_WIDTH.roundToPx() }
    val boxHeight = with(density) { BUBBLE_PHOTO_HEIGHT.roundToPx() }
    val key = "${message.id}/${message.media?.attachmentId}/$boxWidth/$video"
    // Restarts, and so cancels the older load, when the key changes: a stale picture never replaces a newer one.
    val load by produceState<PhotoLoad>(PhotoLoad.Loading, key, host) {
        val cached = host?.photos?.get(key)
        if (cached != null) {
            value = PhotoLoad.Loaded(cached, host.photos.videoLengthMs(key))
            return@produceState
        }
        val opened = host?.opener?.openOrNull(message.id)
        if (opened == null) {
            value = PhotoLoad.Unreadable
            return@produceState
        }
        try {
            val (bitmap, videoMs) = withContext(Dispatchers.IO) { decodeMedia(opened.file, video, boxWidth, boxHeight) }
            if (bitmap == null) {
                value = PhotoLoad.Unreadable
            } else {
                host.photos[key] = bitmap
                if (videoMs != null) host.photos.setVideoLengthMs(key, videoMs)
                value = PhotoLoad.Loaded(bitmap, videoMs)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { opened.close() }
        }
    }
    val description = stringResource(R.string.preview_photo)
    when (val l = load) {
        is PhotoLoad.Loaded -> {
            val label = if (l.videoMs != null) stringResource(R.string.media_play_video) else description
            Box(
                Modifier
                    .widthIn(max = BUBBLE_PHOTO_WIDTH)
                    .heightIn(max = BUBBLE_PHOTO_HEIGHT)
                    .aspectRatio(l.bitmap.width.toFloat() / l.bitmap.height.toFloat())
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { host?.onOpenPhoto?.invoke(message.id) },
            ) {
                Image(
                    bitmap = l.bitmap.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.matchParentSize(),
                )
                if (l.videoMs != null) {
                    Icon(
                        painterResource(R.drawable.ic_play), contentDescription = null, tint = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(48.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(8.dp),
                    )
                    Text(
                        videoClock(l.videoMs), color = Color.White, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        PhotoLoad.Loading, PhotoLoad.Unreadable -> Box(
            Modifier.fillMaxWidth().heightIn(min = 120.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_image), contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
