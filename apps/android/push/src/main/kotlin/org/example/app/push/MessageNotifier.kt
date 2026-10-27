package org.example.app.push

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.example.app.contracts.NotificationOutcome

/**
 * Shows what [NotificationOutcome] says. Plain words only; no mention of how a message is protected.
 * Notifications opened from here carry the extra [EXTRA_CHAT_ID] on the launch intent.
 */
class MessageNotifier(private val context: Context) {
    companion object {
        const val EXTRA_CHAT_ID = "org.example.app.push.CHAT_ID"
    }

    fun show(outcome: NotificationOutcome) {
        when (outcome) {
            is NotificationOutcome.Message ->
                post(NotificationChannels.MESSAGES, "chat:" + outcome.chatId, outcome.senderName, outcome.preview, outcome.chatId)
            is NotificationOutcome.Request ->
                post(NotificationChannels.REQUESTS, "request:" + outcome.chatId, appLabel(), context.getString(R.string.push_request_body), outcome.chatId)
            NotificationOutcome.Generic ->
                post(NotificationChannels.MESSAGES, "generic", appLabel(), context.getString(R.string.push_generic_body), null)
            is NotificationOutcome.NewOwnDevice -> {
                val name = outcome.deviceName
                val body = if (name.isNullOrBlank()) {
                    context.getString(R.string.push_new_device_body_unnamed)
                } else {
                    context.getString(R.string.push_new_device_body, name)
                }
                post(NotificationChannels.DEVICES, "device", context.getString(R.string.push_new_device_title), body, null)
            }
            NotificationOutcome.Nothing, NotificationOutcome.FetchInbox -> Unit
        }
    }

    private fun appLabel(): String = context.applicationInfo.loadLabel(context.packageManager).toString()

    private fun post(channel: String, tag: String, title: String, text: String, chatId: String?) {
        val manager = NotificationManagerCompat.from(context)
        // Without the permission (Android 13+) the system would drop it anyway.
        if (!manager.areNotificationsEnabled()) return
        NotificationChannels.ensure(context)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            chatId?.let { launch.putExtra(EXTRA_CHAT_ID, it) }
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    tag.hashCode(),
                    launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        @Suppress("MissingPermission") // checked above
        manager.notify(tag, 0, builder.build())
    }
}
