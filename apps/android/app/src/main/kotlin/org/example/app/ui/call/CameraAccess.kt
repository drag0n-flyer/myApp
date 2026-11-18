package org.example.app.ui.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.example.app.R
import org.example.app.calls.android.media.CallVideoSurface
import org.example.app.core.calls.CallState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** What the call screen does about the camera for the call's current state. */
enum class CameraStep {
    /** Nothing to do. */
    Ready,

    /** Video is wanted and the permission was never asked in this state: ask now. */
    Ask,

    /** The permission is refused or was revoked: turn video off, the call goes on as audio, say so. */
    AudioOnlyNoPermission,

    /** The permission is held but the camera failed (in use elsewhere, disconnected): audio, say so. */
    AudioOnlyUnavailable,
}

/** Pure decision for [CameraStep]: tested without a device. */
object CameraPolicy {
    fun next(videoWanted: Boolean, granted: Boolean, refused: Boolean, cameraFailed: Boolean): CameraStep = when {
        !videoWanted -> CameraStep.Ready
        !granted -> if (refused) CameraStep.AudioOnlyNoPermission else CameraStep.Ask
        cameraFailed -> CameraStep.AudioOnlyUnavailable
        else -> CameraStep.Ready
    }
}

/** The system's camera permission, swappable in tests. */
class CameraAccess(
    val isGranted: () -> Boolean,
    /** Asks the system; [result] gets whether the camera may now be used. */
    val request: (result: (Boolean) -> Unit) -> Unit,
)

@Composable
fun rememberSystemCameraAccess(): CameraAccess {
    val context = LocalContext.current
    val holder = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        holder[0]?.invoke(ok)
        holder[0] = null
    }
    val launch by rememberUpdatedState(launcher)
    return remember(context) {
        CameraAccess(
            isGranted = { ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED },
            request = { result ->
                holder[0] = result
                launch.launch(Manifest.permission.CAMERA)
            },
        )
    }
}

/** What [CameraGate] shows above the picture: one line, or nothing. */
class CameraGateState(val message: String?)

private enum class CameraNotice { NoPermission, Unavailable }

/**
 * The camera rules of a video call: asks for the permission when video is first wanted (a started or accepted video
 * call, or video turned on), and when it is refused or revoked, or the camera fails, turns video off so the call
 * goes on as audio, with a one-line message.
 */
@Composable
internal fun CameraGate(viewModel: CallViewModel, call: CallState, surface: CallVideoSurface?, access: CameraAccess): CameraGateState {
    val videoWanted = call.isVideoCall && call.isVideoOn && !call.isEnded
    var granted by remember { mutableStateOf(access.isGranted()) }
    var refused by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<CameraNotice?>(null) }
    val problem by (surface?.cameraProblem ?: NO_PROBLEM).collectAsStateWithLifecycle()
    val latestSurface by rememberUpdatedState(surface)

    // The permission can change in system settings while the call screen is behind: look again on every resume.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = access.isGranted()
                if (granted && !now) refused = true // revoked: no new prompt, audio now
                granted = now
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(videoWanted) { if (!videoWanted) refused = false }

    // Video waited for the permission (or the session came after it): start the camera now.
    LaunchedEffect(surface, granted, videoWanted) {
        if (videoWanted && granted) latestSurface?.cameraPermissionGranted()
    }

    val step = CameraPolicy.next(videoWanted, granted, refused, problem)
    LaunchedEffect(step) {
        when (step) {
            CameraStep.Ask -> access.request { ok -> granted = ok; if (!ok) refused = true }
            CameraStep.AudioOnlyNoPermission -> { notice = CameraNotice.NoPermission; viewModel.videoOff() }
            CameraStep.AudioOnlyUnavailable ->
                if (latestSurface?.cameraProblem?.value == true) { notice = CameraNotice.Unavailable; viewModel.videoOff() }
            CameraStep.Ready -> if (videoWanted) notice = null
        }
    }
    return CameraGateState(
        when (notice) {
            CameraNotice.NoPermission -> stringResource(R.string.call_camera_off)
            CameraNotice.Unavailable -> stringResource(R.string.call_camera_unavailable)
            null -> null
        },
    )
}

private val NO_PROBLEM = kotlinx.coroutines.flow.MutableStateFlow(false)
