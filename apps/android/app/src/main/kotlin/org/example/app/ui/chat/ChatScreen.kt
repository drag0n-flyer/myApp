package org.example.app.ui.chat

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.example.app.R
import org.example.app.core.chats.ChatRow
import org.example.app.core.chats.ChatSearch
import org.example.app.core.chats.MessageHit
import org.example.app.core.chats.ChatTimeline
import org.example.app.core.chats.MediaState
import org.example.app.core.chats.MessageAction
import org.example.app.core.chats.MessageActions
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SendProblem
import org.example.app.core.settings.MediaKind
import org.example.app.ui.Avatar
import org.example.app.ui.lock.LocalExpectReturn
import org.example.app.ui.media.AttachmentOpener
import org.example.app.ui.media.AudioManagerFocus
import org.example.app.ui.media.rememberAttachmentPickers
import org.example.app.ui.media.rememberVoiceControls
import org.example.app.ui.media.MediaActions
import org.example.app.ui.media.MediaHost
import org.example.app.ui.media.PhotoCache
import org.example.app.ui.media.MediaPlayerEngine
import org.example.app.ui.media.PhotoViewerScreen
import org.example.app.ui.media.VoicePlayer
import org.example.app.ui.previewText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    myId: String,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    /** Null hides the call buttons (no live calls before E6). */
    onCall: ((video: Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    /** Message request bar. */
    onAccept: () -> Unit = {},
    /** Called with the contact to block; the screen shows an error instead when the chat cannot be resolved. */
    onBlock: (contactId: String) -> Unit = {},
    onReport: () -> Unit = {},
    /** A text was forwarded: open the chat it went to. */
    onForward: (chatId: String) -> Unit = {},
    /** A message to scroll to and highlight when the screen opens (a search hit). */
    highlightMessageId: String? = null,
    /** Decrypts a downloaded photo, file or voice message so it can be shown; null shows placeholders only. */
    opener: AttachmentOpener? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val zone = remember { ZoneId.systemDefault() }

    val messages = state.messages
    // Newest at the bottom: the list is reversed, so index 0 is the newest row.
    val rowsNewestFirst = remember(messages) { ChatTimeline.rows(messages, viewModel.firstUnreadId, zone).asReversed() }
    val rowsRef by rememberUpdatedState(rowsNewestFirst)
    val byId = remember(messages) { messages.associateBy { it.id } }
    val byIdRef by rememberUpdatedState(byId)
    val newest = messages.lastOrNull()

    var replying by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var text by rememberSaveable { mutableStateOf(viewModel.draft()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var forwardText by rememberSaveable { mutableStateOf<String?>(null) }
    var highlighted by remember { mutableStateOf<String?>(null) }
    var blockError by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // Search inside this chat: the hits (newest first, null while looking), the one shown, and where the list was.
    var results by remember { mutableStateOf<List<MessageHit>?>(null) }
    var current by rememberSaveable { mutableStateOf<Int?>(null) }
    var restoreTo by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Media: the decoded photos live as long as this screen, and so does the one-line notice at the bottom.
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var notice by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(resources.getString(it))
            notice = null
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.sendProblem.collect {
            if (it != null) {
                notice = if (it == SendProblem.Reason.TooBig) R.string.media_file_too_big else R.string.media_file_unreadable
                viewModel.sendProblemShown()
            }
        }
    }
    val photos = remember { PhotoCache() }
    DisposableEffect(photos) { onDispose { photos.clear() } }
    var viewingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    val expectReturn by rememberUpdatedState(LocalExpectReturn.current)
    val mediaActions = remember(context, opener) { MediaActions(context, opener, scope, notify = { notice = it }, expectReturn = { expectReturn() }) }
    val pickers = rememberAttachmentPickers(onAttachment = viewModel::sendAttachment, notify = { notice = it })
    val voiceControls = rememberVoiceControls(chatKey = "$myId/${viewModel.chatId}", onNote = { viewModel.sendAttachment(it.path, MediaKind.Voice) }, notify = { notice = it })
    val capture by voiceControls.capture.state.collectAsState()
    val voiceHint by voiceControls.capture.hint.collectAsState()
    // One voice message plays at a time; it stops when the screen is left or the app goes to the background.
    val voice = remember(opener) { VoicePlayer(scope, opener, MediaPlayerEngine.factory, focus = AudioManagerFocus(context)) }
    DisposableEffect(voice) { onDispose { voice.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { voice.stop() }
    val mediaHost = remember(opener, photos, mediaActions, voice) {
        MediaHost(
            opener, photos,
            onOpenPhoto = { viewingPhoto = it },
            onOpenFile = mediaActions::openFile,
            onShareFile = mediaActions::shareFile,
            voice = voice,
        )
    }

    // Jump to a message, loading older pages first if it is not loaded. Gives up quietly when history ends before it.
    suspend fun jumpTo(id: String) {
        try {
            if (!viewModel.ensureLoaded(id)) return
            viewModel.state.first { s -> s.messages.any { it.id == id } }
            var frames = 0
            while (rowsRef.none { it.key == id } && frames++ < 60) androidx.compose.runtime.withFrameNanos { }
            val index = rowsRef.indexOfFirst { it.key == id }
            if (index < 0) return
            // The list is reversed, so a negative offset moves the row up from the bottom edge, towards the middle.
            list.animateScrollToItem(index, -(list.layoutInfo.viewportSize.height / 3))
        } finally {
            // Scrolled, gave up or cancelled: say where the list is, then let trimming resume. Never left set.
            viewModel.finishJump()
            viewModel.setAtBottom(list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0)
        }
        highlighted = id
        delay(HIGHLIGHT_MS)
        if (highlighted == id) highlighted = null
    }

    // Follow new messages only when already at the newest one, or when the user sent it.
    var previousNewestId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastSeenNewestId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(newest?.id) {
        if (newest != null) {
            val first = list.layoutInfo.visibleItemsInfo.firstOrNull()
            val opening = previousNewestId == null
            val atNewest = opening || (first?.key == previousNewestId && list.firstVisibleItemScrollOffset == 0)
            if (opening && viewModel.firstUnreadId != null && rowsRef.any { it is ChatRow.UnreadMarker }) {
                // Open at the "Unread messages" line, near the top, with the unread messages below it.
                val marker = rowsRef.indexOfFirst { it is ChatRow.UnreadMarker }
                list.scrollToItem(marker)
                val info = list.layoutInfo
                val row = info.visibleItemsInfo.firstOrNull { it.index == marker }
                if (row != null) list.scrollToItem(marker, -(info.viewportSize.height - row.size).coerceAtLeast(0))
                lastSeenNewestId = newest.id
            } else if (atNewest || newest.senderId == myId) {
                list.scrollToItem(0)
                lastSeenNewestId = newest.id
            }
            previousNewestId = newest.id
        }
    }
    val atBottom by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 } }
    LaunchedEffect(atBottom, newest?.id) { if (atBottom) lastSeenNewestId = newest?.id }
    LaunchedEffect(atBottom) { viewModel.setAtBottom(atBottom) }
    val unseen = ChatTimeline.unseenCount(messages, lastSeenNewestId)

    // Ask for the next older page when the oldest rows come within reach.
    LaunchedEffect(list) {
        snapshotFlow { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to rowsRef.size }
            .collect { (last, total) -> if (last >= 0) viewModel.maybeLoadOlder(total - 1 - last) }
    }

    LaunchedEffect(highlightMessageId) {
        // Once per chat screen: coming back from chat info must not jump again.
        if (highlightMessageId != null && viewModel.takeHighlight(highlightMessageId)) jumpTo(highlightMessageId)
    }
    fun openSearch() {
        if (searching) return
        restoreTo = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        searching = true
    }
    fun closeSearch() {
        searching = false
        query = ""
        results = null
        current = null
        // Back to where the person was before searching.
        restoreTo?.let { (index, offset) -> scope.launch { list.scrollToItem(index, offset) } }
        restoreTo = null
    }
    fun move(direction: ChatSearch.Direction) {
        current = ChatSearch.step(results.orEmpty(), current, direction)
    }
    // Chat info's Search button.
    LaunchedEffect(Unit) {
        ChatSearchRequests.pending.collect { if (ChatSearchRequests.take(viewModel.chatId)) openSearch() }
    }
    // Look for the typed words once typing pauses; the newest hit is shown first.
    LaunchedEffect(searching, query) {
        results = null
        current = null
        if (!searching || query.isBlank()) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        val found = viewModel.search(query)
        results = found
        current = ChatSearch.step(found, null, ChatSearch.Direction.Next)
    }
    // Jump to the hit that is shown, loading older pages as needed, and flash it.
    LaunchedEffect(results, current) {
        val hit = current?.let { results?.getOrNull(it) } ?: return@LaunchedEffect
        jumpTo(hit.messageId)
    }

    // A bar or dialog tied to a message that vanished or can no longer take the action closes by itself.
    LaunchedEffect(byId, editing, replying, confirmDelete) {
        editing?.let { id ->
            val m = byId[id]
            if (!MessageActions.stillApplies(MessageAction.Edit, m, m?.senderId == myId)) { editing = null; text = viewModel.draft() }
        }
        replying?.let { id ->
            val m = byId[id]
            if (!MessageActions.stillApplies(MessageAction.Reply, m, m?.senderId == myId)) replying = null
        }
        confirmDelete?.let { id ->
            val m = byId[id]
            if (!MessageActions.stillApplies(MessageAction.Delete, m, m?.senderId == myId)) confirmDelete = null
        }
    }

    fun perform(action: MessageAction, id: String) {
        val m = byIdRef[id]
        // The menu was built from an earlier copy: drop the action if it no longer applies.
        if (m == null || !MessageActions.stillApplies(action, m, m.senderId == myId)) return
        val body = (m.content as? MessageContent.Text)?.text
        when (action) {
            MessageAction.Reply -> { editing = null; replying = id }
            MessageAction.React -> Unit
            MessageAction.Copy -> if (body != null) scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", body))) }
            MessageAction.Forward -> forwardText = body
            MessageAction.Edit -> if (body != null) { replying = null; editing = id; text = body }
            MessageAction.Delete -> confirmDelete = id
        }
    }

    val bar: ComposerBar? = when {
        editing != null -> ComposerBar.Editing
        replying != null -> byId[replying]?.let { m ->
            ComposerBar.Replying(if (m.senderId == myId) stringResource(R.string.you) else state.contactName, previewText(m.preview))
        }
        else -> null
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (searching) {
                val hits = results
                val status = when {
                    hits == null -> null
                    hits.isEmpty() -> stringResource(R.string.chat_search_no_results)
                    else -> stringResource(R.string.chat_search_count, (current ?: 0) + 1, hits.size)
                }
                SearchBar(
                    query, onQuery = { query = it }, onClose = ::closeSearch,
                    status = status, canMove = !hits.isNullOrEmpty(),
                    onPrevious = { move(ChatSearch.Direction.Previous) }, onNext = { move(ChatSearch.Direction.Next) },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back)) }
                    },
                    title = {
                        Row(Modifier.clickable(role = Role.Button, onClick = onOpenInfo), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Avatar(state.contactName, size = 36.dp, colorKey = state.chat?.contactId)
                            Text(state.contactName, maxLines = 1)
                        }
                    },
                    actions = {
                        IconButton(onClick = ::openSearch) { Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.chat_search)) }
                        if (onCall != null) IconButton(onClick = { onCall(false) }) { Icon(painterResource(R.drawable.ic_call), contentDescription = stringResource(R.string.call_audio)) }
                        if (onCall != null) IconButton(onClick = { onCall(true) }) { Icon(painterResource(R.drawable.ic_videocam), contentDescription = stringResource(R.string.call_video)) }
                    },
                )
            }
        },
        bottomBar = {
            if (searching) {
                // The search field is the only input while searching.
            } else if (state.isRequest) {
                RequestBar(
                    onAccept = onAccept,
                    onBlock = { viewModel.blockTarget()?.let(onBlock) ?: run { blockError = true } },
                    onReport = onReport,
                )
            } else {
                Composer(
                    enabled = !state.sendingPaused,
                    text = text,
                    onTextChange = { text = it; if (editing == null) viewModel.setDraft(it) },
                    onSend = { sent ->
                        val target = editing
                        if (target != null) {
                            // Dropped when the message changed meanwhile; the bar closes either way.
                            viewModel.edit(target, sent)
                            editing = null
                            text = viewModel.draft()
                        } else if (viewModel.send(sent, replying)) {
                            replying = null
                            text = ""
                            viewModel.setDraft("")
                        }
                    },
                    onAttach = pickers::start,
                    onRecordVoice = voiceControls.activate,
                    showCamera = pickers.cameraAvailable,
                    voice = VoiceUi(
                        state = capture,
                        hint = voiceHint,
                        onPress = voiceControls.press,
                        onDrag = voiceControls.capture::drag,
                        onLift = voiceControls.capture::lift,
                        onInterrupt = voiceControls.capture::interrupted,
                        onStop = voiceControls.capture::stop,
                        onDelete = voiceControls.capture::delete,
                        onSend = voiceControls.capture::send,
                    ),
                    bar = bar,
                    onCloseBar = {
                        if (editing != null) text = viewModel.draft()
                        editing = null
                        replying = null
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            LazyColumn(Modifier, state = list, reverseLayout = true, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(rowsNewestFirst, key = { it.key }, contentType = { it::class }) { row ->
                    when (row) {
                        is ChatRow.DateRow -> DateChip(row.day)
                        ChatRow.UnreadMarker -> UnreadLine()
                        is ChatRow.MessageRow -> {
                            val message = row.message
                            val own = message.senderId == myId
                            val reply = message.replyToId?.let { byId[it] }
                            val actions = MessageActions.available(message, own)
                            val autoKind = when (message.content) {
                                is MessageContent.Photo -> MediaKind.Photo
                                is MessageContent.File -> MediaKind.File
                                is MessageContent.Voice -> MediaKind.Voice
                                else -> null
                            }
                            // The item re-enters composition on scroll-back and rotation; the ViewModel asks the store only once per id.
                            LaunchedEffect(message.id) {
                                val waiting = (message.media?.phase ?: MediaState.Phase.Remote) is MediaState.Phase.Remote
                                if (autoKind != null && waiting) viewModel.requestAutoDownload(message.id, autoKind)
                            }
                            MessageBubble(
                                message = message,
                                isOwn = own,
                                replyTo = reply,
                                replyAuthor = reply?.let { if (it.senderId == myId) null else state.contactName },
                                autoDownloads = viewModel::autoDownloads,
                                onDownload = { viewModel.download(message.id) },
                                onLongPress = if (actions.isEmpty()) null else ({ menuFor = message.id }),
                                menu = {
                                    if (menuFor == message.id) {
                                        MessageMenu(
                                            actions = actions,
                                            onDismiss = { menuFor = null },
                                            onReact = { emoji -> menuFor = null; viewModel.react(emoji, message.id) },
                                            onAction = { action -> menuFor = null; perform(action, message.id) },
                                        )
                                    }
                                },
                                onQuoteClick = { message.replyToId?.let { id -> scope.launch { jumpTo(id) } } },
                                onRetry = { viewModel.retry(message.id) },
                                highlighted = highlighted == message.id,
                                media = mediaHost,
                            )
                        }
                    }
                }
            }
            if (messages.isEmpty()) {
                Text(stringResource(R.string.chat_empty), Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!atBottom) {
                val label = if (unseen > 0) stringResource(R.string.chat_scroll_to_newest_count, unseen) else stringResource(R.string.chat_scroll_to_newest)
                SmallFloatingActionButton(
                    onClick = { scope.launch { list.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).semantics { contentDescription = label },
                ) {
                    BadgedBox(badge = { if (unseen > 0) Badge { Text(unseen.toString()) } }) {
                        Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = null)
                    }
                }
            }
        }
        if (blockError) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { blockError = false },
                title = { Text(stringResource(R.string.err_server)) },
                confirmButton = { TextButton(onClick = { blockError = false }) { Text(stringResource(R.string.close)) } },
            )
        }
        viewingPhoto?.let { id ->
            PhotoViewerScreen(id, opener, onDismiss = { viewingPhoto = null }, onShare = { mediaActions.sharePhoto(id) })
        }
        confirmDelete?.let { id ->
            DeleteDialog(onConfirm = { confirmDelete = null; viewModel.delete(id) }, onDismiss = { confirmDelete = null })
        }
        forwardText?.let { forwarding ->
            ForwardSheet(
                contacts = viewModel.forwardTargets(),
                onPick = { contact ->
                    forwardText = null
                    viewModel.forward(forwarding, contact.id)?.let(onForward)
                },
                onDismiss = { forwardText = null },
            )
        }
    }
}

private const val HIGHLIGHT_MS = 1_500L

/** The reaction row and the actions that apply, in menu order. */
@Composable
private fun MessageMenu(
    actions: List<MessageAction>,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onAction: (MessageAction) -> Unit,
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        if (MessageAction.React in actions) {
            Row(Modifier.padding(horizontal = 8.dp)) {
                for (emoji in MessageActions.reactionEmoji) {
                    val label = stringResource(R.string.chat_react_with, emoji)
                    TextButton(onClick = { onReact(emoji) }, modifier = Modifier.semantics { contentDescription = label }) { Text(emoji) }
                }
            }
        }
        for (action in actions) {
            val label = when (action) {
                MessageAction.Reply -> R.string.chat_action_reply
                MessageAction.Copy -> R.string.chat_action_copy
                MessageAction.Forward -> R.string.chat_action_forward
                MessageAction.Edit -> R.string.chat_action_edit
                MessageAction.Delete -> R.string.chat_action_delete
                MessageAction.React -> null
            } ?: continue
            DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onAction(action) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    /** "3 of 12", "No results", or null while nothing is typed or the search is running. */
    status: String?,
    canMove: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back)) }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.chat_search)) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier.focusRequester(focus),
            )
        },
        actions = {
            if (status != null) Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onPrevious, enabled = canMove) { Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = stringResource(R.string.chat_search_previous)) }
            IconButton(onClick = onNext, enabled = canMove) { Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = stringResource(R.string.chat_search_next)) }
        },
    )
}

private const val SEARCH_DEBOUNCE_MS = 300L
