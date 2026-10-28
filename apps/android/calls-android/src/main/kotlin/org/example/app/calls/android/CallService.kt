package org.example.app.calls.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * The foreground service that keeps a call alive. Type `phoneCall` while it rings; `microphone` is added once the
 * call is answered (or placed), and `camera` for video. The notification is [CallNotifications.incoming] while
 * ringing and [CallNotifications.ongoing] after.
 */
class CallService : Service() {
    companion object {
        private const val ACTION_RING = "org.example.app.calls.service.RING"
        private const val ACTION_ANSWERED = "org.example.app.calls.service.ANSWERED"
        private const val EXTRA_SEND_VIDEO = "org.example.app.calls.service.SEND_VIDEO"

        /** Android 10 has no microphone or camera foreground service types. */
        @SuppressLint("InlinedApi") // the R constants are used only from R on
        fun serviceTypes(answered: Boolean, video: Boolean, sdk: Int = Build.VERSION.SDK_INT): Int {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            if (!answered || sdk < Build.VERSION_CODES.R) return types
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (video) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            return types
        }

        fun ring(context: Context, call: CallInfo) = start(context, ACTION_RING, call, sendVideo = false)

        /** The user answered, or placed the call. [sendVideo] is whether the camera is on. */
        fun answered(context: Context, call: CallInfo, sendVideo: Boolean) = start(context, ACTION_ANSWERED, call, sendVideo)

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }

        private fun start(context: Context, action: String, call: CallInfo, sendVideo: Boolean) {
            val intent = Intent(context, CallService::class.java)
                .setAction(action)
                .putExtras(CallExtras.bundle(call))
                .putExtra(EXTRA_SEND_VIDEO, sendVideo)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val call = CallExtras.read(intent?.extras)
        if (call == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_RING -> foreground(CallNotifications.incoming(this, call), serviceTypes(answered = false, video = false))
            ACTION_ANSWERED -> {
                val video = intent.getBooleanExtra(EXTRA_SEND_VIDEO, false)
                foreground(CallNotifications.ongoing(this, call), allowed(serviceTypes(answered = true, video = video)))
            }
        }
        return START_NOT_STICKY
    }

    /** The microphone and camera types need their runtime permission; without it the call goes on as `phoneCall` only. */
    @SuppressLint("InlinedApi") // clearing bits that Android 10 never sets
    private fun allowed(types: Int): Int {
        var t = types
        if (!granted(Manifest.permission.RECORD_AUDIO)) t = t and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE.inv()
        if (!granted(Manifest.permission.CAMERA)) t = t and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA.inv()
        return t
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun foreground(notification: android.app.Notification, types: Int) {
        try {
            ServiceCompat.startForeground(this, CallNotifications.ID, notification, types)
        } catch (_: RuntimeException) {
            // Started from the background where a microphone or camera type is not allowed: keep the call as a phone call.
            ServiceCompat.startForeground(this, CallNotifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        }
    }
}
