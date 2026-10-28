package org.example.app.ui.qr

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.example.app.R

/**
 * Live camera view that reports the first QR code it reads. The camera is bound to the screen's
 * lifecycle and released when the screen stops or leaves composition, even when the camera
 * provider is still starting up; frames are analysed only as the camera delivers them (no timers).
 * To read again after a rejected code, recompose the scanner under a new `key`. A camera that is
 * missing or busy shows a message instead of crashing.
 */
@Composable
fun QrScanner(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onResult)
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val description = stringResource(R.string.qr_scanner_description)
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val reported = AtomicBoolean(false)
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val binder = CameraBinder<ProcessCameraProvider>(
            bind = { p ->
                val previewUseCase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
            },
            unbind = { it.unbindAll() },
            onFailure = { failed = true },
        )
        analysis.setAnalyzer(executor) { image ->
            try {
                if (!reported.get()) {
                    val plane = image.planes[0]
                    val w = image.width
                    val h = image.height
                    val luma = ByteArray(w * h)
                    val buffer = plane.buffer
                    for (row in 0 until h) {
                        buffer.position(row * plane.rowStride)
                        buffer.get(luma, row * w, w)
                    }
                    val text = QrCodes.decodeLuma(luma, w, h)
                    if (text != null && reported.compareAndSet(false, true)) main.execute { latest(text) }
                }
            } catch (e: Exception) {
                // A bad frame is skipped; the next one is tried.
            } finally {
                image.close()
            }
        }
        val future = try {
            ProcessCameraProvider.getInstance(context)
        } catch (e: Exception) {
            binder.failed(e)
            null
        }
        future?.addListener({
            val provider = try { future.get() } catch (e: Exception) { binder.failed(e); return@addListener }
            binder.ready(provider)
        }, main)
        onDispose {
            binder.release()
            future?.cancel(true)
            executor.shutdown()
        }
    }

    if (failed) {
        Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.qr_camera_unavailable), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
    } else {
        AndroidView({ preview }, modifier.semantics { contentDescription = description })
    }
}

/** Shows the one-line reason, and only then asks for the camera, before it shows [content]. */
@Composable
fun CameraPermissionGate(gate: CameraGate, onRequest: () -> Unit, onOpenSettings: () -> Unit, content: @Composable () -> Unit) {
    if (gate == CameraGate.Granted) {
        content()
    } else {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val reason = if (gate == CameraGate.OpenSettings) R.string.ob_perm_camera_denied else R.string.ob_perm_camera_reason
            Text(stringResource(reason), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            if (gate == CameraGate.OpenSettings) {
                Button(onClick = onOpenSettings) { Text(stringResource(R.string.ob_perm_camera_settings)) }
            } else {
                Button(onClick = onRequest) { Text(stringResource(R.string.ob_perm_camera_allow)) }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** The scanner behind the camera permission, for screens that scan. */
@Composable
fun PermissionedQrScanner(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    fun held() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    fun canAskAgain() = context.findActivity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA) } ?: false
    var granted by remember { mutableStateOf(held()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var again by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        requested = true
        again = !it && canAskAgain()
    }
    // A grant (or a revoke) made in Settings shows up when the person comes back.
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = held()
                if (requested) again = !granted && canAskAgain()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    CameraPermissionGate(
        cameraGate(granted, requested, again),
        onRequest = { launcher.launch(Manifest.permission.CAMERA) },
        onOpenSettings = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { }
        },
    ) {
        QrScanner(onResult, Modifier.fillMaxSize())
    }
}
