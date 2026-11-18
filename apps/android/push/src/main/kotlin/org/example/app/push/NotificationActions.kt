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
    suspend fun reply(chatId: String, text: String, deadline: Deadline): Boolean

    /** Keeps [text] as the chat's unsent draft. */
    suspend fun saveDraft(chatId: String, text: String)
}

/**
 * A point on the monotonic clock. The reply receiver makes one when the broadcast arrives; everything it waits for must
 * finish before it, because a background process that overruns the broadcast is killed without a word.
 */
class Deadline private constructor(private val endNanos: Long, private val nanoTime: () -> Long) {
    /** Milliseconds left, never negative. */
    fun leftMs(): Long = maxOf(0L, (endNanos - nanoTime()) / 1_000_000)

    companion object {
        fun after(ms: Long, nanoTime: () -> Long = System::nanoTime): Deadline = Deadline(nanoTime() + ms * 1_000_000, nanoTime)
    }
}

/** Reply tokens already used, so a broadcast delivered twice sends once. */
internal object ReplyClaims {
    private val used = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * True the first time this token is seen with this text. Two different replies typed before the notification was
     * posted again share a token and are both sent; the same reply delivered twice is sent once. Only a digest of the
     * text is kept.
     */
    fun claim(nonce: String, text: String): Boolean {
        if (used.size > 256) used.clear()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return used.add(nonce + ":" + digest.joinToString("") { "%02x".format(it) })
    }

    fun clear() = used.clear()
}

internal class NotificationActionHandler(
    private val sink: () -> NotificationActionSink?,
    private val notifier: MessageNotifier,
    private val appLockOn: () -> Boolean = { PushRuntime.appLockOn() },
) {
    /** A chat ID is a short, non-blank string. Whether it names a chat is the store's answer. */
    private fun valid(chatId: String?): Boolean =
        chatId != null && chatId.isNotBlank() && chatId.length <= NotificationActions.MAX_ID_LENGTH

    /**
     * Sends [text] to the chat, or says "Not sent". The token is claimed first, so the same broadcast arriving twice
     * sends once; the notification is then posted again with a fresh token, so the next Reply works. A reply that is
     * not sent keeps its text as the chat's draft and says so; it is never dropped quietly.
     */
    suspend fun reply(chatId: String?, text: String?, nonce: String?, deadline: Deadline) {
        if (chatId == null || !valid(chatId) || nonce.isNullOrBlank()) return
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) {
            notifier.refreshChat(chatId) // ends the spinner on the Reply button
            return
        }
        if (!ReplyClaims.claim(nonce, body)) return
        val sink = sink()
        val queued = if (sink == null || appLockOn()) {
            false
        } else {
            try {
                sink.reply(chatId, body, deadline)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        if (queued) {
            notifier.showSent(chatId, body)
            return
        }
        try {
            sink?.saveDraft(chatId, body)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Not sent is still said below.
        }
        notifier.showNotSent(chatId)
        notifier.refreshChat(chatId)
    }

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

    /** From the broadcast arriving to the last thing a reply may wait for. */
    const val REPLY_BUDGET_MS = 9_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Runs the action an intent asks for. Anything else is ignored. */
    internal fun handle(
        context: Context,
        intent: Intent,
        handler: NotificationActionHandler = NotificationActionHandler({ PushRuntime.notificationActions }, MessageNotifier(context)),
        deadline: Deadline = Deadline.after(REPLY_BUDGET_MS),
    ): Job = scope.launch {
        val chatId = intent.getStringExtra(MessageNotifier.EXTRA_CHAT_ID)
        when (intent.action) {
            ACTION_MARK_READ -> handler.markRead(chatId)
            ACTION_REPLY -> {
                val text = androidx.core.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY_TEXT)?.toString()
                handler.reply(chatId, text, intent.getStringExtra(EXTRA_NONCE), deadline)
            }
            else -> Unit
        }
    }

    /**
     * The Reply button. Mutable, because the system must add the typed text to it; explicit, so only [NotificationActionReceiver]
     * can receive it. [nonce] is new for every posting.
     */
    internal fun replyIntent(context: Context, chatId: String, nonce: String): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(
            context,
            (ACTION_REPLY + chatId).hashCode(),
            // The identifier makes the intent differ per chat, so two chats whose ids share a hash code never share a PendingIntent.
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_REPLY).setIdentifier(chatId)
                .putExtra(MessageNotifier.EXTRA_CHAT_ID, chatId).putExtra(EXTRA_NONCE, nonce),
            android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The Mark as read button: immutable, explicit, one per chat. */
    internal fun markReadIntent(context: Context, chatId: String): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(
            context,
            (ACTION_MARK_READ + chatId).hashCode(),
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_MARK_READ).setIdentifier(chatId).putExtra(MessageNotifier.EXTRA_CHAT_ID, chatId),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
