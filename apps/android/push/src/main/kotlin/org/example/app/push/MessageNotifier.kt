package org.example.app.push

import android.app.PendingIntent
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import java.time.Instant
import org.example.app.contracts.NotificationOutcome
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.notify.NotificationPlan

/**
 * Shows what [NotificationOutcome] says. Plain words only; no mention of how a message is protected.
 * Notifications opened from here carry the extra [EXTRA_CHAT_ID] on the launch intent.
 *
 * One notification per chat (tag `chat:<id>`), a `MessagingStyle` holding the chat's newest lines. The lines are
 * read back from the notification that is already showing, so they survive the process. With two or more chats a
 * summary (tag `summary`) groups them and carries the badge number. Text is never logged.
 */
class MessageNotifier(
    private val context: Context,
    private val mutedChatIds: () -> Set<String> = { PushRuntime.mutedChatIds() },
    private val appLockOn: () -> Boolean = { PushRuntime.appLockOn() },
) {
    companion object {
        const val EXTRA_CHAT_ID = "org.example.app.push.CHAT_ID"
        internal const val CHAT_TAG = "chat:"
        internal const val CALL_TAG = "call:"
        internal const val SUMMARY_TAG = "summary"
        private const val GROUP_KEY = "org.example.app.push.CHATS"
        private const val SELF_KEY = "self"
        private const val EXTRA_HIDDEN = "org.example.app.push.HIDDEN"
    }

    /** A line already in a chat's notification. */
    private class Entry(val text: String, val at: Long, val mine: Boolean)

    fun show(outcome: NotificationOutcome) {
        when (outcome) {
            is NotificationOutcome.Message -> postChat(outcome.chatId, outcome.senderName, Entry(outcome.preview, System.currentTimeMillis(), mine = false))
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

    /** The call ended before it was answered. Own tag, so it does not replace the chat's unread-message notification. Never muted. */
    fun showMissedCall(chatId: String, callerName: String) =
        post(NotificationChannels.MESSAGES, CALL_TAG + chatId, callerName, context.getString(R.string.push_missed_call_body), chatId)

    /** A ring from a non-contact never rang. Quiet, on the requests channel, and names nobody. */
    fun showStrangerMissedCall() =
        post(
            NotificationChannels.REQUESTS, "request-call", appLabel(), context.getString(R.string.push_missed_call_body), null,
            quiet = true,
        )

    /** The chat was read: its notification goes (and the summary, once fewer than two chats remain). */
    fun cancelChat(chatId: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(CHAT_TAG + chatId, 0)
        manager.cancel(CALL_TAG + chatId, 0)
        refreshSummary()
    }

    /** Removes the generic notice once the real notification is shown. */
    fun cancelGeneric() = NotificationManagerCompat.from(context).cancel("generic", 0)

    private fun appLabel(): String = context.applicationInfo.loadLabel(context.packageManager).toString()

    private fun active(tag: String): StatusBarNotification? =
        NotificationManagerCompat.from(context).activeNotifications.firstOrNull { it.tag == tag && it.id == 0 }

    private fun history(chatId: String): List<Entry> {
        val n = active(CHAT_TAG + chatId)?.notification ?: return emptyList()
        if (n.extras.getBoolean(EXTRA_HIDDEN)) return emptyList() // placeholder lines carry nothing worth keeping
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) ?: return emptyList()
        return style.messages.map { Entry(it.text?.toString().orEmpty(), it.timestamp, it.person?.key == SELF_KEY) }
    }

    private fun postChat(chatId: String, senderName: String, added: Entry) {
        val manager = NotificationManagerCompat.from(context)
        // Without the permission (Android 13+) the system would drop it anyway.
        if (!manager.areNotificationsEnabled()) return
        NotificationChannels.ensure(context)
        val locked = appLockOn()
        val entries = if (locked) listOf(added) else history(chatId) + added
        val unread = entries.mapIndexed { i, e ->
            Message(
                id = "n$i", senderId = if (e.mine) SELF_KEY else chatId, sentAt = Instant.ofEpochMilli(e.at),
                content = MessageContent.Text(e.text), status = DeliveryStatus.Delivered, replyToId = null, reactions = emptyList(),
            )
        }
        val plan = NotificationPlan.forChat(chatId, unread, senderName, muted = chatId in mutedChatIds(), appLocked = locked)
        val mineKeys = entries.filter { it.mine }.map { it.at to it.text }.toSet()

        val me = Person.Builder().setName(context.getString(R.string.push_you)).setKey(SELF_KEY).build()
        val them = Person.Builder().setName(if (plan.hideContent) appLabel() else plan.title).setKey(chatId).build()
        val style = NotificationCompat.MessagingStyle(me)
        val shown = if (plan.hideContent) listOf(context.getString(R.string.push_generic_body)) else plan.lines.map { it.text }
        plan.lines.forEachIndexed { i, line ->
            val own = !plan.hideContent && (line.sentAt.toEpochMilli() to line.text) in mineKeys
            style.addMessage(shown.getOrElse(i) { line.text }, line.sentAt.toEpochMilli(), if (own) me else them)
        }
        val builder = NotificationCompat.Builder(context, if (plan.silent) NotificationChannels.MUTED else NotificationChannels.MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(if (plan.hideContent) appLabel() else plan.title)
            .setContentText(shown.last())
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .addExtras(android.os.Bundle().apply { putBoolean(EXTRA_HIDDEN, plan.hideContent) })
        if (plan.silent) builder.setPriority(NotificationCompat.PRIORITY_LOW).setSilent(true)
        openChat(builder, CHAT_TAG + chatId, chatId)
        builder.addAction(0, context.getString(R.string.push_action_mark_read), NotificationActions.markReadIntent(context, chatId))
        @Suppress("MissingPermission") // checked above
        manager.notify(CHAT_TAG + chatId, 0, builder.build())
        refreshSummary()
    }

    /** Posts the summary while two or more chats are showing, removes it otherwise. Its number is the unmuted chats. */
    private fun refreshSummary() {
        val manager = NotificationManagerCompat.from(context)
        val chats = manager.activeNotifications.filter { it.tag?.startsWith(CHAT_TAG) == true && it.id == 0 }
        if (!NotificationPlan.needsSummary(chats.map { it.tag }.toSet())) {
            manager.cancel(SUMMARY_TAG, 0)
            return
        }
        if (!manager.areNotificationsEnabled()) return
        val unmuted = chats.count { it.notification.channelId != NotificationChannels.MUTED }
        val summary = NotificationCompat.Builder(context, NotificationChannels.MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(appLabel())
            .setContentText(context.getString(R.string.push_generic_body))
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setNumber(unmuted)
            .setSilent(true)
            .setAutoCancel(true)
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            summary.setContentIntent(PendingIntent.getActivity(context, SUMMARY_TAG.hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        @Suppress("MissingPermission") // checked above
        manager.notify(SUMMARY_TAG, 0, summary.build())
    }

    private fun openChat(builder: NotificationCompat.Builder, tag: String, chatId: String?) {
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            chatId?.let { launch.putExtra(EXTRA_CHAT_ID, it) }
            builder.setContentIntent(
                PendingIntent.getActivity(context, tag.hashCode(), launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
    }

    private fun post(channel: String, tag: String, title: String, text: String, chatId: String?, quiet: Boolean = false) {
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
        if (quiet) builder.setPriority(NotificationCompat.PRIORITY_LOW).setSilent(true).setOnlyAlertOnce(true)
        openChat(builder, tag, chatId)
        @Suppress("MissingPermission") // checked above
        manager.notify(tag, 0, builder.build())
    }
}
