package org.example.app.ui.chat

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.settings.MediaKind
import org.example.app.messaging.content.TextLimit

/** Attach, message field, and a mic that turns into send once there is text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Composer(
    enabled: Boolean,
    onSend: (String) -> Unit,
    onAttach: (MediaKind) -> Unit,
    onRecordVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var sheet by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = { sheet = true }, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_attach), contentDescription = stringResource(R.string.attach))
        }
        TextField(
            value = text,
            onValueChange = { text = TextLimit.truncate(it) },
            enabled = enabled,
            placeholder = { Text(stringResource(if (enabled) R.string.message_hint else R.string.sending_paused_hint)) },
            maxLines = 5,
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f),
        )
        if (text.isNotBlank()) {
            IconButton(onClick = { onSend(text); text = "" }, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_send), contentDescription = stringResource(R.string.send))
            }
        } else {
            IconButton(onClick = onRecordVoice, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.record_voice))
            }
        }
    }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Column(Modifier.padding(bottom = 16.dp)) {
                AttachRow(R.string.attach_photo, R.drawable.ic_attach) { sheet = false; onAttach(MediaKind.Photo) }
                AttachRow(R.string.attach_camera, R.drawable.ic_videocam) { sheet = false; onAttach(MediaKind.Photo) }
                AttachRow(R.string.attach_file, R.drawable.ic_attach) { sheet = false; onAttach(MediaKind.File) }
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
