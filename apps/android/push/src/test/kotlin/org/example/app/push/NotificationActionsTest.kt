package org.example.app.push

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.NotificationOutcome
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

internal class FakeSink(private val known: Set<String> = setOf("c1")) : NotificationActionSink {
    val marked = mutableListOf<String>()
    val replies = mutableListOf<Pair<String, String>>()
    val drafts = mutableListOf<Pair<String, String>>()
    var replyResult: Boolean = true
    var replyFails: Boolean = false

    override suspend fun markRead(chatId: String): Boolean {
        marked += chatId
        return chatId in known
    }

    override suspend fun reply(chatId: String, text: String): Boolean {
        replies += chatId to text
        if (replyFails) throw IllegalStateException("boom")
        return replyResult && chatId in known
    }

    override suspend fun saveDraft(chatId: String, text: String) {
        drafts += chatId to text
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationActionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val notifier get() = MessageNotifier(context, mutedChatIds = { emptySet() }, appLockOn = { false })

    @BeforeTest
    fun setUp() {
        shadowOf(context as Application).grantPermissions("android.permission.POST_NOTIFICATIONS")
    }

    @AfterTest
    fun tearDown() {
        PushRuntime.notificationActions = null
    }

    private fun chat(id: String) = nm.activeNotifications.firstOrNull { it.tag == "chat:$id" }

    private fun action(n: Notification, title: String): Notification.Action? = n.actions?.firstOrNull { it.title == title }

    private fun markReadIntent(chatId: String?, name: String = NotificationActions.ACTION_MARK_READ) =
        Intent(context, NotificationActionReceiver::class.java).setAction(name).apply {
            if (chatId != null) putExtra(MessageNotifier.EXTRA_CHAT_ID, chatId)
        }

    private fun handler(sink: NotificationActionSink?) = NotificationActionHandler({ sink }, notifier)

    @Test
    fun theReceiverIsNotExported() {
        val info = context.packageManager.getReceiverInfo(ComponentName(context, NotificationActionReceiver::class.java), 0)
        assertFalse(info.exported)
    }

    @Test
    fun theNotificationOffersMarkAsReadThroughAnImmutableExplicitBroadcast() {
        notifier.show(NotificationOutcome.Message("c1", "Ana", "hi"))
        val button = assertNotNull(action(chat("c1")!!.notification, "Mark as read"))
        val pi = button.actionIntent
        val shadow = shadowOf(pi)
        assertTrue(shadow.isBroadcastIntent)
        assertTrue(shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        val sent = shadow.savedIntent
        assertEquals(ComponentName(context, NotificationActionReceiver::class.java), sent.component)
        assertEquals(NotificationActions.ACTION_MARK_READ, sent.action)
        assertEquals("c1", sent.getStringExtra(MessageNotifier.EXTRA_CHAT_ID))
    }

    @Test
    fun markAsReadMarksTheChatAndRemovesItsNotification() = runTest {
        notifier.show(NotificationOutcome.Message("c1", "Ana", "hi"))
        val sink = FakeSink()
        NotificationActions.handle(context, markReadIntent("c1"), handler(sink)).join()
        assertEquals(listOf("c1"), sink.marked)
        assertEquals(null, chat("c1"))
    }

    @Test
    fun aChatTheStoreDoesNotKnowIsLeftAlone() = runTest {
        notifier.show(NotificationOutcome.Message("zz", "Ana", "hi"))
        val sink = FakeSink(known = setOf("c1"))
        NotificationActions.handle(context, markReadIntent("zz"), handler(sink)).join()
        assertEquals(listOf("zz"), sink.marked, "the store is asked")
        assertNotNull(chat("zz"), "and said no, so the notification stays")
    }

    @Test
    fun withoutASignedInSessionNothingHappens() = runTest {
        notifier.show(NotificationOutcome.Message("c1", "Ana", "hi"))
        NotificationActions.handle(context, markReadIntent("c1"), handler(null)).join()
        assertNotNull(chat("c1"))
    }

    @Test
    fun malformedIntentsNeverReachTheStore() = runTest {
        val sink = FakeSink()
        val h = handler(sink)
        NotificationActions.handle(context, markReadIntent(null), h).join()
        NotificationActions.handle(context, markReadIntent(""), h).join()
        NotificationActions.handle(context, markReadIntent("   "), h).join()
        NotificationActions.handle(context, markReadIntent("x".repeat(NotificationActions.MAX_ID_LENGTH + 1)), h).join()
        NotificationActions.handle(context, markReadIntent("c1", name = "org.example.app.push.SOMETHING_ELSE"), h).join()
        NotificationActions.handle(context, Intent(), h).join()
        assertTrue(sink.marked.isEmpty(), sink.marked.toString())
    }
}
