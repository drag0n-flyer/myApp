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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.heading
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.example.app.R
import org.example.app.core.calls.DataFormat
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.GalleryGroups
import org.example.app.core.chats.MediaBubbleState
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SafeFileName
import org.example.app.media.videoClock
import org.example.app.ui.dataSizeText
import org.example.app.ui.lock.LocalExpectReturn

/**
 * "Media and files" of one chat: a Media tab (a three-column grid of photos and videos, newest first, grouped by month) and
 * a Files tab (name, size, date). Tapping a downloaded item opens the viewer or the file; one not downloaded yet offers
 * Download. Everything is read from the chat's whole history, not only the part the chat screen has loaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    store: ChatStore,
    chatId: String,
    opener: AttachmentOpener?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chats by store.chats.collectAsStateWithLifecycle()
    val requests by store.requests.collectAsStateWithLifecycle()
    val chat = remember(chats, requests) { chats.firstOrNull { it.id == chatId } ?: requests.firstOrNull { it.id == chatId } }
    // Read again when the chat's messages change (a download finishing, a new photo).
    val media by produceState(emptyList<Message>(), chat?.messages) { value = withContext(Dispatchers.IO) { ChatMediaIndex.load(store, chatId) } }
    val photos = media.filter { ChatMediaIndex.isMedia(it) }
    val files = media.filter { it.content is MessageContent.File && !ChatMediaIndex.isVideo(it) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val expectReturn by rememberUpdatedState(LocalExpectReturn.current)
    var notice by remember { mutableStateOf<Int?>(null) }
    val actions = remember(context, opener) { MediaActions(context, opener, scope, notify = { notice = it }, expectReturn = { expectReturn() }) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    val cache = remember { PhotoCache() }
    DisposableEffect(cache) { onDispose { cache.clear() } }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.info_media_and_files)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.gallery_media)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.gallery_files)) })
            }
            notice?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            if (tab == 0) {
                MediaGrid(photos, opener, cache, onOpen = { viewing = it }, onDownload = { store.download(it, chatId) })
            } else {
                FileList(files, onOpen = { m -> actions.openFile(m.id, SafeFileName.of((m.content as MessageContent.File).name, "file")) }, onDownload = { store.download(it, chatId) })
            }
        }
    }
    viewing?.let { id ->
        val items = remember(media) { ChatMediaIndex.viewerItems(media).let { all -> if (all.any { it.id == id }) all else all + ViewerItem(id, false) } }
        MediaViewerScreen(items, id, opener, onDismiss = { viewing = null }, onShare = actions::sharePhoto, onSave = { actions.save(it.id, it.videoName) })
    }
}

@Composable
private fun MediaGrid(
    photos: List<Message>,
    opener: AttachmentOpener?,
    cache: PhotoCache,
    onOpen: (String) -> Unit,
    onDownload: (String) -> Unit,
) {
    if (photos.isEmpty()) {
        EmptyState(stringResource(R.string.gallery_no_media))
        return
    }
    val zone = remember { ZoneId.systemDefault() }
    val groups = remember(photos) { GalleryGroups.byMonth(photos, zone) }
    val monthFormat = remember { DateTimeFormatter.ofPattern("LLLL yyyy") }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (group in groups) {
            item(key = "month-${group.month}", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    group.month.format(monthFormat),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
                )
            }
            items(group.items, key = { it.id }) { message -> MediaTile(message, opener, cache, onOpen, onDownload) }
        }
    }
}

@Composable
private fun MediaTile(message: Message, opener: AttachmentOpener?, cache: PhotoCache, onOpen: (String) -> Unit, onDownload: (String) -> Unit) {
    val state = rememberBubbleState(message.media, null)
    val description = stringResource(R.string.preview_photo)
    Box(
        Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is MediaBubbleState.Ready -> Thumbnail(message, opener, cache, onOpen)
            is MediaBubbleState.Downloading -> CircularProgressIndicator(
                progress = { state.fraction.toFloat() },
                modifier = Modifier.semantics { contentDescription = description }.size(32.dp),
            )
            MediaBubbleState.NotDownloaded, MediaBubbleState.Failed -> IconButton(onClick = { onDownload(message.id) }) {
                Icon(painterResource(R.drawable.ic_download), contentDescription = stringResource(R.string.download))
            }
        }
    }
}

/** One decoded square: a photo, or a frame of a video with its length. Decrypts the file only for as long as the decode takes. */
@Composable
private fun Thumbnail(message: Message, opener: AttachmentOpener?, cache: PhotoCache, onOpen: (String) -> Unit) {
    val side = with(LocalDensity.current) { 128.dp.roundToPx() }
    val key = "gallery/${message.id}/${message.media?.attachmentId}/$side"
    val loaded by produceState<Pair<Bitmap?, Long?>?>(null, key, opener) {
        val cached = cache[key]
        if (cached != null) {
            value = cached to cache.videoLengthMs(key)
            return@produceState
        }
        val opened = opener?.openOrNull(message.id)
        if (opened == null) {
            value = null to null
            return@produceState
        }
        try {
            val result = withContext(Dispatchers.IO) { decodeMedia(opened.file, ChatMediaIndex.isVideo(message), side, side) }
            result.first?.let { cache[key] = it }
            result.second?.let { cache.setVideoLengthMs(key, it) }
            value = result
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { opened.close() }
        }
    }
    val (bitmap, videoMs) = loaded ?: (null to null)
    val label = stringResource(if (videoMs != null) R.string.media_play_video else R.string.preview_photo)
    Box(Modifier.fillMaxSize().clickable(role = Role.Button) { onOpen(message.id) }.semantics { contentDescription = label }) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(painterResource(R.drawable.ic_image), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
        }
        if (videoMs != null) {
            Icon(
                painterResource(R.drawable.ic_play), contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(36.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(6.dp),
            )
            Text(
                videoClock(videoMs), color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun FileList(files: List<Message>, onOpen: (Message) -> Unit, onDownload: (String) -> Unit) {
    if (files.isEmpty()) {
        EmptyState(stringResource(R.string.gallery_no_files))
        return
    }
    val downloading = stringResource(R.string.downloading)
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val zone = remember { ZoneId.systemDefault() }
    LazyColumn(Modifier.fillMaxSize()) {
        items(files.sortedByDescending { it.sentAt }, key = { it.id }) { message ->
            val file = message.content as MessageContent.File
            val state = rememberBubbleState(message.media, null)
            val name = SafeFileName.of(file.name, "file")
            ListItem(
                modifier = Modifier.then(if (state is MediaBubbleState.Ready) Modifier.clickable(role = Role.Button) { onOpen(message) } else Modifier),
                leadingContent = { Icon(painterResource(R.drawable.ic_file), contentDescription = null) },
                headlineContent = { Text(name) },
                supportingContent = { Text(stringResource(R.string.file_with_size, dateFormat.format(message.sentAt.atZone(zone)), dataSizeText(DataFormat.size(file.bytes)))) },
                trailingContent = {
                    when (state) {
                        is MediaBubbleState.Downloading -> CircularProgressIndicator(
                            progress = { state.fraction.toFloat() },
                            modifier = Modifier.semantics { contentDescription = downloading }.size(24.dp),
                        )
                        MediaBubbleState.NotDownloaded, MediaBubbleState.Failed -> IconButton(onClick = { onDownload(message.id) }) {
                            Icon(painterResource(R.drawable.ic_download), contentDescription = stringResource(R.string.download))
                        }
                        is MediaBubbleState.Ready -> Unit
                    }
                },
            )
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
