package org.example.app.ui.qr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.example.app.R

/**
 * Live camera view that reports the first QR code it reads. The camera is bound to the screen's
 * lifecycle and released when the screen stops or leaves composition; frames are analysed only as
 * the camera delivers them (no timers).
 */
@Composable
fun QrScanner(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onResult)
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val description = stringResource(R.string.qr_scanner_description)

    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val reported = AtomicBoolean(false)
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = future.get()
            provider = p
            val previewUseCase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
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
                } finally {
                    image.close()
                }
            }
            p.unbindAll()
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
        }, main)
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView({ preview }, modifier.semantics { contentDescription = description })
}

/** Shows the one-line reason, and only then asks for the camera, before it shows [content]. */
@Composable
fun CameraPermissionGate(granted: Boolean, onRequest: () -> Unit, content: @Composable () -> Unit) {
    if (granted) {
        content()
    } else {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.ob_perm_camera_reason), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Button(onClick = onRequest) { Text(stringResource(R.string.ob_perm_camera_allow)) }
        }
    }
}

/** The scanner behind the camera permission, for screens that scan. */
@Composable
fun PermissionedQrScanner(onResult: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    CameraPermissionGate(granted, onRequest = { launcher.launch(Manifest.permission.CAMERA) }) {
        QrScanner(onResult, Modifier.fillMaxSize())
    }
}
