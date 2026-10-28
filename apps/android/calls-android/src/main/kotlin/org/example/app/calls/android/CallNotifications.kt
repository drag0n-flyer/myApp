package org.example.app.calls.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import org.example.app.push.MessageNotifier
import org.example.app.push.NotificationChannels

/**
 * Call notifications on the `calls` channel. The incoming one is a CallStyle notification with a full-screen
 * intent to the app's lock-screen call screen when the app may use one (Android 14 lets the user take that away),
 * and a heads-up with Answer and Decline otherwise. It is posted directly, with no foreground service, and makes
 * no sound: [Ringer] rings. Answer, Decline and Hang up open [IncomingCallActivity] directly: no broadcast
 * trampoline. No text says how the call is protected.
 */
object CallNotifications {
    /** The ongoing call (the foreground service's notification). */
    const val ID = 0x0CA1
    private const val TAG_INCOMING = "call-incoming"

    /** Below Android 14 the install-time `USE_FULL_SCREEN_INTENT` permission always holds. */
    fun canFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun incoming(context: Context, call: CallInfo, fullScreen: Boolean = canFullScreen(context)): Notification {
        NotificationChannels.ensure(context)
        val show = showScreen(context, call)
        val decline = activity(context, IncomingCallActivity.ACTION_DECLINE, call, REQUEST_DECLINE)
        val answer = activity(context, IncomingCallActivity.ACTION_ANSWER, call, REQUEST_ANSWER)
        val builder = base(context, call)
            .setContentText(context.getString(if (call.video) R.string.calls_incoming_video else R.string.calls_incoming_audio))
            .setContentIntent(show)
            .setSilent(true)
        if (fullScreen) {
            // CallStyle needs a full-screen intent or a foreground service, and nothing runs in the foreground while it rings.
            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(person(context, call), decline, answer).setIsVideo(call.video))
            builder.setFullScreenIntent(show, true)
        } else {
            builder.addAction(0, context.getString(R.string.calls_action_decline), decline)
            builder.addAction(0, context.getString(R.string.calls_action_answer), answer)
        }
        return builder.build()
    }

    /** Posts the ringing call. */
    fun showIncoming(context: Context, call: CallInfo) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        @Suppress("MissingPermission") // checked above
        manager.notify(TAG_INCOMING, ID, incoming(context, call))
    }

    fun cancelIncoming(context: Context) = NotificationManagerCompat.from(context).cancel(TAG_INCOMING, ID)

    /**
     * The app's lock-screen call screen (an activity in `:app` that answers [IncomingCallActivity.ACTION_SHOW]):
     * the Android form of the iPhone's incoming-call screen.
     */
    fun showScreen(context: Context, call: CallInfo): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_SHOW,
            Intent(IncomingCallActivity.ACTION_SHOW)
                .setPackage(context.packageName)
                .putExtras(CallExtras.bundle(call))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun ongoing(context: Context, call: CallInfo): Notification {
        NotificationChannels.ensure(context)
        val style = NotificationCompat.CallStyle.forOngoingCall(
            person(context, call),
            activity(context, IncomingCallActivity.ACTION_HANG_UP, call, REQUEST_HANG_UP),
        ).setIsVideo(call.video)
        return base(context, call)
            .setContentText(context.getString(R.string.calls_ongoing))
            .setContentIntent(activity(context, IncomingCallActivity.ACTION_OPEN, call, REQUEST_OPEN))
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
    private const val REQUEST_OPEN = 5
}
