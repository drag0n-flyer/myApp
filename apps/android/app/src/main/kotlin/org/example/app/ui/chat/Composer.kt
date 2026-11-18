package org.example.app.ui.chat

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.ui.media.AttachChoice
import org.example.app.ui.media.CaptureState
import org.example.app.core.chats.VoiceGesture
import org.example.app.messaging.content.TextLimit

/** The bar above the composer: who is being replied to, or that a message is being edited. */
sealed interface ComposerBar {
    data class Replying(val author: String, val preview: String) : ComposerBar
    data object Editing : ComposerBar
}

/**
 * Attach, message field, and a mic that turns into send once there is text. The text belongs to the caller, which keeps
 * a draft per chat and fills the field when a message is edited. [bar] shows above the field with a close button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Composer(
    enabled: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onAttach: (AttachChoice) -> Unit,
    onRecordVoice: () -> Unit,
    modifier: Modifier = Modifier,
    bar: ComposerBar? = null,
    onCloseBar: () -> Unit = {},
    /** False hides the Camera row (a phone without a camera). */
    showCamera: Boolean = true,
    /** The voice message in progress (hold bar, locked bar, preview) and the microphone's gesture callbacks. */
    voice: VoiceUi = VoiceUi.None,
    /** The preview made for the first link of the text, shown above the field with a remove control. */
    preview: org.example.app.core.chats.LinkPreviewCard? = null,
    onRemovePreview: () -> Unit = {},
    /** True while a photo's or file's caption is being edited: sending nothing then removes the caption. */
    allowBlankSend: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val recordingPhase = (voice.state as? CaptureState.Recording)?.phase
    LaunchedEffect(voice.state is CaptureState.Recording) {
        if (voice.state is CaptureState.Recording) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    LaunchedEffect(recordingPhase) {
        if (recordingPhase == VoiceGesture.State.willCancel || recordingPhase == VoiceGesture.State.willLock) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    var across by remember { mutableFloatStateOf(0f) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    // The caller owns the text; the cursor lives here and goes to the end whenever the caller replaces the text.
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
    Column(modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
        if (bar != null) {
            val label = when (bar) {
                is ComposerBar.Replying -> stringResource(R.string.chat_replying_to, bar.author)
                ComposerBar.Editing -> stringResource(R.string.chat_editing)
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (bar is ComposerBar.Replying) {
                        Text(bar.preview, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onCloseBar, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.chat_bar_close))
                }
            }
        }
        if (preview != null) {
            LinkPreviewCardView(preview, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), onRemove = onRemovePreview)
        }
        val locked = voice.state as? CaptureState.Recording
        if (voice.hint && voice.state == CaptureState.Idle) VoiceHint()
        if (voice.state is CaptureState.Preview) {
            PreviewBar(voice.state, voice)
        } else if (locked != null && !voice.holding) {
            LockedBar(locked, voice)
        } else Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (locked != null) {
                HoldBar(locked, across, Modifier.weight(1f))
            } else {
                IconButton(onClick = { sheet = true }, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_attach), contentDescription = stringResource(R.string.attach))
                }
                TextField(
                    value = field,
                    onValueChange = { value ->
                        val cut = TextLimit.truncate(value.text)
                        field = if (cut == value.text) value else TextFieldValue(cut, TextRange(cut.length))
                        onTextChange(cut)
                    },
                    enabled = enabled,
                    placeholder = { Text(stringResource(if (enabled) R.string.message_hint else R.string.sending_paused_hint)) },
                    maxLines = 5,
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                )
            }
            if (text.isNotBlank() || allowBlankSend) {
                IconButton(onClick = { onSend(text) }, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_send), contentDescription = stringResource(R.string.send))
                }
            } else {
                MicButton(enabled, voice, onRecordVoice) { across = it }
            }
        }
    }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Column(Modifier.padding(bottom = 16.dp)) {
                AttachRow(R.string.attach_photo, R.drawable.ic_image) { sheet = false; onAttach(AttachChoice.Photo) }
                if (showCamera) AttachRow(R.string.attach_camera, R.drawable.ic_camera) { sheet = false; onAttach(AttachChoice.Camera) }
                AttachRow(R.string.attach_file, R.drawable.ic_file) { sheet = false; onAttach(AttachChoice.File) }
            }
        }
    }
}

@Composable
private fun AttachRow(label: Int, icon: Int, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        headlineContent = { Text(stringResource(label)) },
    )
}
