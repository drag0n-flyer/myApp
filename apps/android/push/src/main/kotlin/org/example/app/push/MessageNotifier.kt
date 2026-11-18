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

/** A line already in a chat's notification. */
private class Entry(val text: String, val at: Long, val mine: Boolean)

/**
 * What this process last did to a chat's notification. The notification manager applies posts and cancels
 * asynchronously, so a read of its list straight after one can be stale; for [FRESH_MS] this is trusted instead.
 * `gone`: the chat's notification was just cancelled.
 */
private class Snapshot(
    val title: String?, val hidden: Boolean, val muted: Boolean, val entries: List<Entry>, val at: Long, val gone: Boolean = false,
    /** The latest expiry among the lines when every one of them expires, else null (the notification stays until read). */
    val expiresAt: Long? = null,
)

/** Shared by every [MessageNotifier] of the process (they are made on use), and guarded by [lock]. */
private object NotifierState {
    const val FRESH_MS = 3_000L
    val lock = Any()
    val recent = HashMap<String, Snapshot>()
}

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
        internal const val NOT_SENT_TAG = "notsent:"
        internal const val SUMMARY_TAG = "summary"
        private const val GROUP_KEY = "org.example.app.push.CHATS"
        private const val SELF_KEY = "self"
        private const val EXTRA_HIDDEN = "org.example.app.push.HIDDEN"
        private const val EXTRA_EXPIRES = "org.example.app.push.EXPIRES"

        /** Forgets what this process last did to each chat's notification. For tests, which share one process. */
        fun forgetRecent() = synchronized(NotifierState.lock) { NotifierState.recent.clear() }
    }

    fun show(outcome: NotificationOutcome) {
        when (outcome) {
            is NotificationOutcome.Message ->
                postChat(outcome.chatId, outcome.senderName, Entry(outcome.preview, System.currentTimeMillis(), mine = false), addedExpiresAt = outcome.expiresAtMs)
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

    /**
     * The call ended before it was answered. Own tag, so it does not replace the chat's unread-message notification.
     * Never muted. With app lock on it names nobody: the app's name and the same words, nothing else.
     */
    fun showMissedCall(chatId: String, callerName: String, hideName: Boolean = false) =
        post(
            NotificationChannels.MESSAGES, CALL_TAG + chatId,
            if (hideName || appLockOn()) appLabel() else callerName,
            context.getString(R.string.push_missed_call_body), chatId,
        )

    /** A ring from a non-contact never rang. Quiet, on the requests channel, and names nobody. */
    fun showStrangerMissedCall() =
        post(
            NotificationChannels.REQUESTS, "request-call", appLabel(), context.getString(R.string.push_missed_call_body), null,
            quiet = true,
        )

    /** The chat was read: its notification goes (and the summary, once fewer than two chats remain). */
    fun cancelChat(chatId: String) = synchronized(NotifierState.lock) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(CHAT_TAG + chatId, 0)
        manager.cancel(CALL_TAG + chatId, 0)
        manager.cancel(NOT_SENT_TAG + chatId, 0)
        NotifierState.recent[CHAT_TAG + chatId] = Snapshot(null, false, false, emptyList(), System.currentTimeMillis(), gone = true)
        refreshSummary(cancelled = CHAT_TAG + chatId)
    }

    /**
     * Messages in this chat expired: only its message notification goes. A missed-call notice and a "not sent" notice are
     * not about those messages and stay.
     */
    fun cancelChatMessages(chatId: String) = synchronized(NotifierState.lock) {
        NotificationManagerCompat.from(context).cancel(CHAT_TAG + chatId, 0)
        NotifierState.recent[CHAT_TAG + chatId] = Snapshot(null, false, false, emptyList(), System.currentTimeMillis(), gone = true)
        refreshSummary(cancelled = CHAT_TAG + chatId)
    }

    /**
     * Some of the chat's messages expired but others are still unread: its notification shows those (text and time),
     * without alerting again. Does nothing when the chat has no notification showing (a dismissed one stays dismissed).
     * [expiresAtMs] is the latest expiry among them when all expire, else null.
     */
    fun replaceLines(chatId: String, title: String, lines: List<Pair<String, Long>>, expiresAtMs: Long?) = synchronized(NotifierState.lock) {
        if (stateOf(chatId) == null || lines.isEmpty()) return@synchronized
        postChatLocked(chatId, title, null, alert = false, forceLocked = false, replace = lines.map { Entry(it.first, it.second, mine = false) }, addedExpiresAt = expiresAtMs)
    }

    /** The reply was queued: the chat's notification gets the sent line, without alerting again. Nothing to update once it was dismissed. */
    fun showSent(chatId: String, text: String) {
        val title = shownTitle(chatId) ?: return
        postChat(chatId, title, Entry(text, System.currentTimeMillis(), mine = true), alert = false)
    }

    /** The reply could not be sent: "Not sent", which opens the chat (the text is its draft). Names nobody while app lock is on. */
    fun showNotSent(chatId: String, hideName: Boolean = false) = synchronized(NotifierState.lock) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return@synchronized
        val name = if (hideName || appLockOn()) null else shownTitle(chatId)
        NotificationChannels.ensure(context)
        val builder = NotificationCompat.Builder(context, NotificationChannels.MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(context.getString(R.string.push_not_sent))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
        if (name != null) builder.setContentText(name)
        openChat(builder, NOT_SENT_TAG + chatId, chatId)
        @Suppress("MissingPermission") // checked above
        manager.notify(NOT_SENT_TAG + chatId, 0, builder.build())
    }

    /** Posts the chat's notification again as it is, with a fresh Reply token (and no sound). */
    fun refreshChat(chatId: String) {
        val title = shownTitle(chatId) ?: return
        postChat(chatId, title, null, alert = false)
    }

    /** The sender's name on the chat's showing notification; null when there is none or it hides its content. */
    private fun shownTitle(chatId: String): String? = stateOf(chatId)?.takeIf { !it.hidden }?.title

    /**
     * What the chat's notification shows now: what this process just did to it, or else what the system lists. Null when
     * there is none. Call with the lock held.
     */
    private fun stateOf(chatId: String): Snapshot? {
        val tag = CHAT_TAG + chatId
        NotifierState.recent[tag]?.takeIf { System.currentTimeMillis() - it.at < NotifierState.FRESH_MS }?.let { return if (it.gone) null else it }
        val n = active(tag)?.notification ?: return null
        val muted = n.channelId == NotificationChannels.MUTED
        val expires = n.extras.getLong(EXTRA_EXPIRES, 0L).takeIf { it > 0 }
        if (n.extras.getBoolean(EXTRA_HIDDEN)) return Snapshot(null, true, muted, emptyList(), System.currentTimeMillis(), expiresAt = expires)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val entries = style?.messages?.map { Entry(it.text?.toString().orEmpty(), it.timestamp, it.person?.key == SELF_KEY) }.orEmpty()
        return Snapshot(n.extras.getString("android.title"), false, muted, entries, System.currentTimeMillis(), expiresAt = expires)
    }

    /**
     * App lock was just turned on: every showing chat notification is posted again in its locked form (no sender, no
     * history, no Reply), and so are a "Not sent" notice and a missed call, which then name nobody. Safe to call twice.
     */
    fun hideShownContent() = synchronized(NotifierState.lock) {
        val manager = NotificationManagerCompat.from(context)
        val ids = (
            manager.activeNotifications.mapNotNull { it.tag?.takeIf { t -> t.startsWith(CHAT_TAG) } } +
                NotifierState.recent.filter { !it.value.gone && System.currentTimeMillis() - it.value.at < NotifierState.FRESH_MS }.keys
            ).toSet() - NotifierState.recent.filter { it.value.gone && System.currentTimeMillis() - it.value.at < NotifierState.FRESH_MS }.keys
        for (tag in ids) {
            val chatId = tag.removePrefix(CHAT_TAG)
            if (stateOf(chatId)?.hidden == true) continue
            postChat(chatId, "", null, alert = false, forceLocked = true)
        }
        val notSent = manager.activeNotifications.mapNotNull { it.tag?.takeIf { t -> t.startsWith(NOT_SENT_TAG) } }
        for (tag in notSent) showNotSent(tag.removePrefix(NOT_SENT_TAG), hideName = true)
        val calls = manager.activeNotifications.mapNotNull { it.tag?.takeIf { t -> t.startsWith(CALL_TAG) } }
        for (tag in calls) showMissedCall(tag.removePrefix(CALL_TAG), "", hideName = true)
        refreshSummary()
    }

    /** Removes the generic notice once the real notification is shown. */
    fun cancelGeneric() = NotificationManagerCompat.from(context).cancel("generic", 0)

    private fun appLabel(): String = context.applicationInfo.loadLabel(context.packageManager).toString()

    private fun active(tag: String): StatusBarNotification? =
        NotificationManagerCompat.from(context).activeNotifications.firstOrNull { it.tag == tag && it.id == 0 }

    /** Placeholder lines of a locked notification carry nothing worth keeping. */
    private fun history(chatId: String): List<Entry> = stateOf(chatId)?.takeIf { !it.hidden }?.entries.orEmpty()

    /** One post at a time: the history is read, the notification built and posted, and the cache updated under one lock. */
    private fun postChat(chatId: String, senderName: String, added: Entry?, alert: Boolean = true, forceLocked: Boolean = false, addedExpiresAt: Long? = null) =
        synchronized(NotifierState.lock) { postChatLocked(chatId, senderName, added, alert, forceLocked, addedExpiresAt = addedExpiresAt) }

    private fun postChatLocked(
        chatId: String, senderName: String, added: Entry?, alert: Boolean, forceLocked: Boolean,
        /** Lines that replace the notification's history (some messages expired). */
        replace: List<Entry>? = null,
        /** When [added] (or every line of [replace]) disappears; null when it stays. */
        addedExpiresAt: Long? = null,
    ) {
        val manager = NotificationManagerCompat.from(context)
        // Without the permission (Android 13+) the system would drop it anyway.
        if (!manager.areNotificationsEnabled()) return
        NotificationChannels.ensure(context)
        val locked = forceLocked || appLockOn()
        val priorLines = if (locked || replace != null) emptyList() else history(chatId)
        // Was a notification showing (even one that hides its content under the app lock)? Then whatever it holds counts.
        val prior = stateOf(chatId)
        val showing = prior != null
        val priorExpires = prior?.expiresAt
        val entries = when {
            locked -> listOf(added ?: Entry("", System.currentTimeMillis(), false))
            replace != null -> replace
            else -> priorLines + listOfNotNull(added)
        }
        if (entries.isEmpty()) return
        // A notification must not outlive every message in it. It times out at the latest expiry of its lines, but only
        // when each of them expires; one line that stays keeps it until read. My own reply line changes nothing.
        val expiresAt: Long? = when {
            replace != null -> if (locked) priorExpires else addedExpiresAt
            added == null || added.mine -> priorExpires
            !showing -> addedExpiresAt
            priorExpires == null || addedExpiresAt == null -> null
            else -> maxOf(priorExpires, addedExpiresAt)
        }
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
            .addExtras(android.os.Bundle().apply { putBoolean(EXTRA_HIDDEN, plan.hideContent); putLong(EXTRA_EXPIRES, expiresAt ?: 0L) })
        if (expiresAt != null) builder.setTimeoutAfter(maxOf(1L, expiresAt - System.currentTimeMillis()))
        if (plan.silent) builder.setPriority(NotificationCompat.PRIORITY_LOW)
        if (plan.silent || !alert) builder.setSilent(true)
        if (!alert) builder.setOnlyAlertOnce(true)
        openChat(builder, CHAT_TAG + chatId, chatId)
        if (!plan.hideContent) {
            // Not offered with app lock on: a reply typed there would be a way around the lock.
            val input = androidx.core.app.RemoteInput.Builder(NotificationActions.KEY_REPLY_TEXT).setLabel(context.getString(R.string.push_action_reply)).build()
            val reply = NotificationCompat.Action.Builder(
                0, context.getString(R.string.push_action_reply),
                NotificationActions.replyIntent(context, chatId, java.util.UUID.randomUUID().toString()),
            )
                .addRemoteInput(input)
                .setAllowGeneratedReplies(true)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .build()
            builder.addAction(reply)
        }
        builder.addAction(0, context.getString(R.string.push_action_mark_read), NotificationActions.markReadIntent(context, chatId))
        @Suppress("MissingPermission") // checked above
        manager.notify(CHAT_TAG + chatId, 0, builder.build())
        val kept = if (plan.hideContent) emptyList() else plan.lines.map { Entry(it.text, it.sentAt.toEpochMilli(), (it.sentAt.toEpochMilli() to it.text) in mineKeys) }
        NotifierState.recent[CHAT_TAG + chatId] = Snapshot(
            if (plan.hideContent) null else plan.title, plan.hideContent, plan.silent, kept, System.currentTimeMillis(), expiresAt = expiresAt,
        )
        refreshSummary(posted = CHAT_TAG + chatId to plan.silent)
    }

    /** Posts the summary while two or more chats are showing, removes it otherwise. Its number is the unmuted chats. */
    internal fun refreshSummary(posted: Pair<String, Boolean>? = null, cancelled: String? = null) = synchronized(NotifierState.lock) {
        val manager = NotificationManagerCompat.from(context)
        // tag -> muted. The system's list can lag behind what this process just posted or cancelled, so what it did
        // within the last moments, and the call's own `posted` and `cancelled`, win over the list.
        val chats = LinkedHashMap<String, Boolean>()
        for (n in manager.activeNotifications) {
            if (n.tag?.startsWith(CHAT_TAG) == true && n.id == 0) chats[n.tag] = n.notification.channelId == NotificationChannels.MUTED
        }
        val now = System.currentTimeMillis()
        for ((tag, snap) in NotifierState.recent) {
            if (now - snap.at >= NotifierState.FRESH_MS) continue
            if (snap.gone) chats.remove(tag) else chats[tag] = snap.muted
        }
        cancelled?.let { chats.remove(it) }
        posted?.let { chats[it.first] = it.second }
        if (!NotificationPlan.needsSummary(chats.keys)) {
            manager.cancel(SUMMARY_TAG, 0)
            return@synchronized
        }
        if (!manager.areNotificationsEnabled()) return@synchronized
        val unmuted = chats.values.count { !it }
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
            // Unique per notification, so two tags whose hash codes collide never share (and rewrite) one PendingIntent.
            launch.setIdentifier(tag)
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
