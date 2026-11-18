package org.example.app.ui.chatinfo

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.app.profile.ProfilePhotoCache
import org.example.app.ui.Avatar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.app.R
import org.example.app.profile.ProfilePhoto
import org.example.app.ui.lock.LocalExpectReturn

/**
 * "Choose a photo" / "Take a photo" for a group photo, with the same picker and the same encoder as the profile photo:
 * the picture is read with a size cap, centre-cropped to a square, scaled to 256 px and encoded as a JPEG that passes
 * `ProfilePhotoRule`, all off the main thread. [onPhoto] gets that JPEG only; a picture that cannot be used shows
 * "Couldn't use that photo". The pickers use the app lock's "expected return" allowance.
 */
@Composable
fun GroupPhotoChooser(show: Boolean, onDismiss: () -> Unit, onPhoto: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val expectReturn = LocalExpectReturn.current
    var error by remember { mutableStateOf(false) }

    fun prepare(source: ByteArray?) {
        scope.launch {
            val jpeg = if (source == null || source.isEmpty()) null else withContext(Dispatchers.Default) { ProfilePhoto.prepare(source) }
            if (jpeg == null) error = true else onPhoto(jpeg)
        }
    }

    val library = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try { context.contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_PICKED_BYTES) } } catch (e: Exception) { null }
            }
            prepare(bytes)
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) scope.launch {
            val bytes = withContext(Dispatchers.Default) {
                ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
            }
            prepare(bytes)
        }
    }

    if (show) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.profile_change_photo)) },
            text = {
                Column {
                    TextButton(onClick = {
                        onDismiss(); expectReturn()
                        library.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text(stringResource(R.string.profile_photo_choose)) }
                    TextButton(onClick = {
                        onDismiss(); expectReturn()
                        try { camera.launch(null) } catch (e: ActivityNotFoundException) { error = true }
                    }) { Text(stringResource(R.string.profile_photo_take)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (error) {
        AlertDialog(
            onDismissRequest = { error = false },
            text = { Text(stringResource(R.string.profile_photo_error)) },
            confirmButton = { TextButton(onClick = { error = false }) { Text(stringResource(R.string.group_ok)) } },
        )
    }
}

/**
 * The chosen photo as the group will show it (a circle), or the coloured letter while there is none. The bytes are checked
 * against `ProfilePhotoRule` again and decoded sub-sampled, off the main thread, by the shared photo cache.
 */
@Composable
fun GroupPhotoTile(photo: ByteArray?, name: String, size: androidx.compose.ui.unit.Dp = 84.dp) {
    val bitmap by produceState<ImageBitmap?>(null, photo) {
        value = if (photo == null) null else withContext(Dispatchers.Default) { ProfilePhotoCache.get(photo)?.asImageBitmap() }
    }
    val shown = bitmap
    if (shown == null) {
        Avatar(name.ifBlank { stringResource(R.string.group_untitled) }, size = size, colorKey = null)
    } else {
        Image(
            shown, contentDescription = stringResource(R.string.group_photo_label),
            modifier = Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop,
        )
    }
}

/** At most [limit] bytes of [input], or null when there are more (nothing larger is ever held in memory). */
private fun readAtMost(input: InputStream, limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > limit) return null
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

private const val MAX_PICKED_BYTES = 32 * 1024 * 1024
