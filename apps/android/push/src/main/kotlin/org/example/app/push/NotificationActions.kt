package org.example.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What the notification buttons do to the signed-in account. The app implements it over the live session
 * (`PushWiring`); until then, and while signed out, [PushRuntime.notificationActions] is null and a button does nothing.
 * Every chat ID reaches these functions only after [NotificationActionHandler] checked its shape; the sink checks it
 * against the store.
 */
interface NotificationActionSink {
    /** Marks the chat read (and sends the read receipt when the setting allows). False when no such chat exists. */
    suspend fun markRead(chatId: String): Boolean
}

internal class NotificationActionHandler(
    private val sink: () -> NotificationActionSink?,
    private val notifier: MessageNotifier,
) {
    /** A chat ID is a short, non-blank string. Whether it names a chat is the store's answer. */
    private fun valid(chatId: String?): Boolean =
        chatId != null && chatId.isNotBlank() && chatId.length <= NotificationActions.MAX_ID_LENGTH

    suspend fun markRead(chatId: String?) {
        if (chatId == null || !valid(chatId)) return
        val sink = sink() ?: return // signed out: leave the notification for the person to open or swipe
        val known = try {
            sink.markRead(chatId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (known) notifier.cancelChat(chatId)
    }
}

/**
 * Receives the Mark as read button. Not exported: only this app's own `PendingIntent`s reach it, and each chat ID is
 * still checked against the store before anything happens.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        NotificationActions.handle(context.applicationContext, intent).invokeOnCompletion { pending?.finish() }
    }
}

object NotificationActions {
    const val ACTION_MARK_READ = "org.example.app.push.MARK_READ"
    internal const val MAX_ID_LENGTH = 256

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Runs the action an intent asks for. Anything else is ignored. */
    internal fun handle(
        context: Context,
        intent: Intent,
        handler: NotificationActionHandler = NotificationActionHandler({ PushRuntime.notificationActions }, MessageNotifier(context)),
    ): Job = scope.launch {
        val chatId = intent.getStringExtra(MessageNotifier.EXTRA_CHAT_ID)
        when (intent.action) {
            ACTION_MARK_READ -> handler.markRead(chatId)
            else -> Unit
        }
    }

    /** The Mark as read button: immutable, explicit, one per chat. */
    internal fun markReadIntent(context: Context, chatId: String): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(
            context,
            (ACTION_MARK_READ + chatId).hashCode(),
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_MARK_READ).putExtra(MessageNotifier.EXTRA_CHAT_ID, chatId),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
