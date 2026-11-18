package org.example.app.ui.media

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import org.example.app.ui.lock.LocalExpectReturn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.app.R
import org.example.app.core.chats.Picked
import org.example.app.core.settings.MediaKind
import org.example.app.media.MediaCache
import org.example.app.media.VoicePrep
import org.example.app.media.VoiceRecorder

/** The rows of the attach sheet. */
enum class AttachChoice { Photo, Camera, File }

/**
 * The three ways to add something to a message. [start] opens the system's own screen for each: the photo picker, the
 * document picker, the camera. A choice that cannot go ahead (permission refused, nothing to handle it) says why in one
 * line through the `notify` the picker was built with, and leaves the chat usable.
 */
class AttachmentPickers(val cameraAvailable: Boolean, private val launch: (AttachChoice) -> Unit) {
    fun start(choice: AttachChoice) = launch(choice)
}

/**
 * Photo and video (`PickMultipleVisualMedia`), File (`OpenDocument`, copied into app storage first) and Camera
 * (`TakePicture` into a file of the app's own camera folder, after the camera permission). A file goes to [onAttachment]
 * as a path the sender can read; photos and videos go to [onPicked], which shows the send preview (nothing is staged or
 * uploaded before the person sends). Runs its file work off the main thread.
 */
@Composable
fun rememberAttachmentPickers(
    onAttachment: (path: String, kind: MediaKind) -> Unit,
    notify: (Int) -> Unit,
    onPicked: (List<Picked>) -> Unit = { items -> items.forEach { onAttachment(it.path, it.kind) } },
): AttachmentPickers {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attach by rememberUpdatedState(onAttachment)
    val picked by rememberUpdatedState(onPicked)
    val say by rememberUpdatedState(notify)
    val expectReturn by rememberUpdatedState(LocalExpectReturn.current)
    // Where the camera is writing; kept across a rotation and the camera app being in front.
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }

    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val fromPicker = uris.filter { PickedFiles.isFromPicker(context, it) }
        if (fromPicker.size < uris.size) say(R.string.media_file_unreadable)
        if (fromPicker.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch { picked(withContext(Dispatchers.IO) { fromPicker.map { describePicked(context, it) } }) }
    }

    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (!PickedFiles.isFromPicker(context, uri)) {
            say(R.string.media_file_unreadable)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = PickedFiles.copy(File(MediaCache.outgoingDir(context), "files"), displayName(context, uri)) {
                context.contentResolver.openInputStream(uri)
            }
            when (result) {
                is PickedFiles.Result.Copied -> attach(result.file.path, MediaKind.File)
                PickedFiles.Result.TooBig -> say(R.string.media_file_too_big)
                PickedFiles.Result.Unreadable -> say(R.string.media_file_unreadable)
            }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val path = cameraFile
        cameraFile = null
        if (path != null) {
            scope.launch {
                val usable = withContext(Dispatchers.IO) {
                    val file = File(path)
                    (taken && file.length() > 0).also { if (!it) file.delete() }
                }
                if (usable) picked(listOf(Picked(path, MediaKind.Photo, withContext(Dispatchers.IO) { File(path).length() })))
            }
        }
    }

    fun startCamera() {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                File(SharedFiles.cameraDir(context), "${UUID.randomUUID()}.jpg").also { it.parentFile?.mkdirs() }
            }
            cameraFile = file.path
            try {
                expectReturn()
                camera.launch(SharedFiles.uriFor(context, file))
            } catch (e: ActivityNotFoundException) {
                cameraFile = null
                say(R.string.qr_camera_unavailable)
            } catch (e: SecurityException) {
                cameraFile = null
                say(R.string.qr_camera_unavailable)
            }
        }
    }

    // The app declares the camera permission, so the system camera refuses to open for it until the permission is granted.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else say(R.string.media_camera_off)
    }

    val hasCamera = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

    return remember(hasCamera, photo, document, cameraPermission) {
        AttachmentPickers(hasCamera) { choice ->
            try {
                when (choice) {
                    AttachChoice.Photo -> expectReturn().also { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                    AttachChoice.File -> expectReturn().also { document.launch(arrayOf("*/*")) }
                    AttachChoice.Camera ->
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            startCamera()
                        } else {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                }
            } catch (e: ActivityNotFoundException) {
                say(R.string.media_file_unreadable)
            }
        }
    }
}

/** A picked photo or video: a video by the type the provider reports, and its size (0 when the provider does not say). Blocking. */
private fun describePicked(context: Context, uri: Uri): Picked {
    val type = try { context.contentResolver.getType(uri) } catch (e: Exception) { null }
    val size = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
    } catch (e: Exception) {
        0L
    }
    val video = org.example.app.core.chats.ChatMedia.isVideo(displayName(context, uri).orEmpty(), type.orEmpty())
    return Picked(uri.toString(), if (video) MediaKind.Video else MediaKind.Photo, size)
}

/** What the document provider calls the file; null when it does not say. The caller cleans it before use. */
internal fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    } ?: uri.lastPathSegment
} catch (e: Exception) {
    uri.lastPathSegment
}

/** The microphone button's side of recording: the capture, and the tap that asks for the permission first. */
class VoiceControls(val capture: VoiceCapture, val requestStart: () -> Unit)

/**
 * A [VoiceCapture] on the real recorder. A tap on the microphone starts recording after the microphone permission (asked
 * on first use); refusing it says so in one line. Leaving the screen, or the app going to the background, throws a
 * recording in progress away: a note recorded with the microphone cut off would be silence.
 */
@Composable
fun rememberVoiceControls(onNote: (File) -> Unit, notify: (Int) -> Unit): VoiceControls {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val note by rememberUpdatedState(onNote)
    val say by rememberUpdatedState(notify)
    val capture = remember(context) {
        val dir = MediaCache.outgoingDir(context)
        VoiceCapture(scope, VoiceRecorderPort(VoiceRecorder(context, dir), dir), { note(it) }, { say(it) }, check = { VoicePrep.prepare(it) != null })
    }
    DisposableEffect(capture) { onDispose { capture.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { capture.cancel() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) capture.begin() else say(R.string.media_mic_off)
    }
    return remember(capture, permission) {
        VoiceControls(capture) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                capture.begin()
            } else {
                permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
