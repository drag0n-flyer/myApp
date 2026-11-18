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

    /**
     * Puts [text] in the chat's outbox, the same durable queue the composer uses, through the session the app already
     * holds. True only when it is queued. False when the chat is unknown or a message request, the text is too long to
     * send whole, or there is no session; nothing was queued then.
     */
    suspend fun reply(chatId: String, text: String): Boolean

    /** Keeps [text] as the chat's unsent draft. */
    suspend fun saveDraft(chatId: String, text: String)
}

/** Reply tokens already used, so a broadcast delivered twice sends once. */
internal object ReplyClaims {
    fun claim(nonce: String): Boolean = true

    fun clear() = Unit
}

internal class NotificationActionHandler(
    private val sink: () -> NotificationActionSink?,
    private val notifier: MessageNotifier,
    private val appLockOn: () -> Boolean = { PushRuntime.appLockOn() },
) {
    /** A chat ID is a short, non-blank string. Whether it names a chat is the store's answer. */
    private fun valid(chatId: String?): Boolean =
        chatId != null && chatId.isNotBlank() && chatId.length <= NotificationActions.MAX_ID_LENGTH

    suspend fun reply(chatId: String?, text: String?, nonce: String?) = Unit

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
    const val ACTION_REPLY = "org.example.app.push.REPLY"
    const val KEY_REPLY_TEXT = "org.example.app.push.REPLY_TEXT"
    const val EXTRA_NONCE = "org.example.app.push.NONCE"
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
