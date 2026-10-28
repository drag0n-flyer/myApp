package org.example.app.calls.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import org.example.app.push.MessageNotifier
import org.example.app.push.NotificationChannels

/**
 * Call notifications on the `calls` channel. The incoming one is a CallStyle notification with a full-screen
 * intent when the app may use one (Android 14 lets the user take that away), and a heads-up otherwise. Answer,
 * Decline and Hang up open [IncomingCallActivity] directly: no broadcast trampoline. No text says how the call
 * is protected.
 */
object CallNotifications {
    const val ID = 0x0CA1

    /** Below Android 14 the install-time `USE_FULL_SCREEN_INTENT` permission always holds. */
    fun canFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun incoming(context: Context, call: CallInfo, fullScreen: Boolean = canFullScreen(context)): Notification {
        NotificationChannels.ensure(context)
        val show = activity(context, IncomingCallActivity.ACTION_SHOW, call, REQUEST_SHOW)
        val style = NotificationCompat.CallStyle.forIncomingCall(
            person(context, call),
            activity(context, IncomingCallActivity.ACTION_DECLINE, call, REQUEST_DECLINE),
            activity(context, IncomingCallActivity.ACTION_ANSWER, call, REQUEST_ANSWER),
        ).setIsVideo(call.video)
        val builder = base(context, call)
            .setContentText(context.getString(if (call.video) R.string.calls_incoming_video else R.string.calls_incoming_audio))
            .setContentIntent(show)
            .setStyle(style)
        if (fullScreen) builder.setFullScreenIntent(show, true)
        return builder.build()
    }

    fun ongoing(context: Context, call: CallInfo): Notification {
        NotificationChannels.ensure(context)
        val style = NotificationCompat.CallStyle.forOngoingCall(
            person(context, call),
            activity(context, IncomingCallActivity.ACTION_HANG_UP, call, REQUEST_HANG_UP),
        ).setIsVideo(call.video)
        return base(context, call)
            .setContentText(context.getString(R.string.calls_ongoing))
            .setContentIntent(activity(context, IncomingCallActivity.ACTION_SHOW, call, REQUEST_SHOW))
            .setStyle(style)
            .setSilent(true)
            .build()
    }

    /** "Missed call" in the chat with the caller, like a push that came after the call ended. */
    fun missed(context: Context, call: CallInfo) =
        MessageNotifier(context).showMissedCall(call.contact.id, CallExtras.displayName(context, call))

    private fun base(context: Context, call: CallInfo) =
        NotificationCompat.Builder(context, NotificationChannels.CALLS)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(CallExtras.displayName(context, call))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)

    private fun person(context: Context, call: CallInfo): Person =
        Person.Builder().setName(CallExtras.displayName(context, call)).setImportant(true).build()

    private fun activity(context: Context, action: String, call: CallInfo, request: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            request,
            IncomingCallActivity.intent(context, action, call),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private const val REQUEST_SHOW = 1
    private const val REQUEST_ANSWER = 2
    private const val REQUEST_DECLINE = 3
    private const val REQUEST_HANG_UP = 4
}
