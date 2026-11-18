package org.example.app.push

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.NotificationOutcome
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationReplyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private var locked = false
    private fun notifier() = MessageNotifier(context, mutedChatIds = { emptySet() }, appLockOn = { locked })

    @BeforeTest
    fun setUp() {
        shadowOf(context as Application).grantPermissions("android.permission.POST_NOTIFICATIONS")
        ReplyClaims.clear()
    }

    private fun chat(id: String = "c1"): StatusBarNotification? = nm.activeNotifications.firstOrNull { it.tag == "chat:$id" }

    private fun replyAction(n: Notification) = n.actions?.firstOrNull { it.title == "Reply" }

    private fun nonceOf(n: Notification): String =
        checkNotNull(shadowOf(checkNotNull(replyAction(n)).actionIntent).savedIntent.getStringExtra(NotificationActions.EXTRA_NONCE))

    private fun replyIntent(chatId: String?, text: String?, nonce: String?): Intent {
        val i = Intent(context, NotificationActionReceiver::class.java).setAction(NotificationActions.ACTION_REPLY)
        if (chatId != null) i.putExtra(MessageNotifier.EXTRA_CHAT_ID, chatId)
        if (nonce != null) i.putExtra(NotificationActions.EXTRA_NONCE, nonce)
        if (text != null) {
            val key = NotificationActions.KEY_REPLY_TEXT
            RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(key).build()), i, Bundle().apply { putCharSequence(key, text) })
        }
        return i
    }

    private fun handler(sink: NotificationActionSink?) = NotificationActionHandler({ sink }, notifier(), { locked })

    private suspend fun run(sink: NotificationActionSink?, intent: Intent) = NotificationActions.handle(context, intent, handler(sink)).join()

    private fun lines(n: Notification) =
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)!!.messages

    @Test
    fun theNotificationOffersReplyWithAMutableExplicitRemoteInputIntent() {
        notifier().show(NotificationOutcome.Message("c1", "Ana", "hi"))
        val action = checkNotNull(replyAction(chat()!!.notification))
        assertEquals(NotificationActions.KEY_REPLY_TEXT, action.remoteInputs.single().resultKey)
        val shadow = shadowOf(action.actionIntent)
        assertTrue(shadow.isBroadcastIntent)
        assertTrue(shadow.flags and PendingIntent.FLAG_MUTABLE != 0)
        assertEquals(0, shadow.flags and PendingIntent.FLAG_IMMUTABLE)
        assertEquals(ComponentName(context, NotificationActionReceiver::class.java), shadow.savedIntent.component)
        assertEquals("c1", shadow.savedIntent.getStringExtra(MessageNotifier.EXTRA_CHAT_ID))
    }

    @Test
    fun appLockOffersNoReply() {
        locked = true
        notifier().show(NotificationOutcome.Message("c1", "Ana", "hi"))
        val n = chat()!!.notification
        assertNull(replyAction(n))
        assertNotNull(n.actions?.firstOrNull { it.title == "Mark as read" })
    }

    @Test
    fun aSentReplyIsQueuedOnceAndShownAsYourLine() = runTest {
        notifier().show(NotificationOutcome.Message("c1", "Ana", "hi"))
        val before = nonceOf(chat()!!.notification)
        val sink = FakeSink()
        run(sink, replyIntent("c1", "on my way", before))
        assertEquals(listOf("c1" to "on my way"), sink.replies)
        assertTrue(sink.drafts.isEmpty())
        val msgs = lines(chat()!!.notification)
        assertEquals(listOf("hi", "on my way"), msgs.map { it.text.toString() })
        assertEquals("self", msgs.last().person?.key)
        assertNotEquals(before, nonceOf(chat()!!.notification), "the button carries a fresh token")
        assertNull(nm.activeNotifications.firstOrNull { it.tag == "notsent:c1" })
    }

    @Test
    fun aRetriedBroadcastDoesNotSendTwice() = runTest {
        notifier().show(NotificationOutcome.Message("c1", "Ana", "hi"))
        val nonce = nonceOf(chat()!!.notification)
        val sink = FakeSink()
        run(sink, replyIntent("c1", "once", nonce))
        run(sink, replyIntent("c1", "once", nonce))
        assertEquals(1, sink.replies.size)
    }

    @Test
    fun aReplyThatCouldNotBeQueuedSaysNotSentAndKeepsTheTextAsTheDraft() = runTest {
        notifier().show(NotificationOutcome.Message("c1", "Ana", "hi"))
        val before = nonceOf(chat()!!.notification)
        val sink = FakeSink().apply { replyResult = false }
        run(sink, replyIntent("c1", "lost?", before))
        assertEquals(listOf("c1" to "lost?"), sink.drafts)
        val n = checkNotNull(nm.activeNotifications.firstOrNull { it.tag == "notsent:c1" }).notification
        assertEquals("Not sent", n.extras.getString("android.title"))
        assertEquals("Ana", n.extras.getString("android.text"))
        val open = shadowOf(n.contentIntent).savedIntent
        assertEquals("c1", open.getStringExtra(MessageNotifier.EXTRA_CHAT_ID))
        // The chat's notification still offers Reply, with a token the next attempt can use.
        assertNotEquals(before, nonceOf(chat()!!.notification))
        assertEquals(listOf("hi"), lines(chat()!!.notification).map { it.text.toString() })
    }

    @Test
    fun aFailingStoreIsTreatedAsNotSent() = runTest {
        notifier().show(NotificationOutcome.Message("c1", "Ana", "hi"))
        val sink = FakeSink().apply { replyFails = true }
        run(sink, replyIntent("c1", "text", nonceOf(chat()!!.notification)))
        assertEquals(listOf("c1" to "text"), sink.drafts)
        assertNotNull(nm.activeNotifications.firstOrNull { it.tag == "notsent:c1" })
    }

    @Test
    fun aLockThatTurnedOnAfterPostingRefusesTheReplyButKeepsTheText() = runTest {
        notifier().show(NotificationOutcome.Message("c1", "Ana", "hi"))
        val nonce = nonceOf(chat()!!.notification)
        locked = true
        val sink = FakeSink()
        run(sink, replyIntent("c1", "private", nonce))
        assertTrue(sink.replies.isEmpty())
        assertEquals(listOf("c1" to "private"), sink.drafts)
        val n = checkNotNull(nm.activeNotifications.firstOrNull { it.tag == "notsent:c1" }).notification
        assertEquals("Not sent", n.extras.getString("android.title"))
        assertNull(n.extras.getString("android.text"), "no name while locked")
    }

    @Test
    fun noSessionSaysNotSent() = runTest {
        notifier().show(NotificationOutcome.Message("c1", "Ana", "hi"))
        run(null, replyIntent("c1", "text", nonceOf(chat()!!.notification)))
        assertNotNull(nm.activeNotifications.firstOrNull { it.tag == "notsent:c1" })
    }

    @Test
    fun aBlankReplySendsNothingAndLeavesTheNotificationUsable() = runTest {
        notifier().show(NotificationOutcome.Message("c1", "Ana", "hi"))
        val sink = FakeSink()
        run(sink, replyIntent("c1", "   ", nonceOf(chat()!!.notification)))
        assertTrue(sink.replies.isEmpty() && sink.drafts.isEmpty())
        assertNotNull(replyAction(chat()!!.notification))
        assertNull(nm.activeNotifications.firstOrNull { it.tag == "notsent:c1" })
    }

    @Test
    fun malformedReplyIntentsNeverReachTheStore() = runTest {
        val sink = FakeSink()
        run(sink, replyIntent(null, "x", "n1"))
        run(sink, replyIntent("", "x", "n2"))
        run(sink, replyIntent("c".repeat(NotificationActions.MAX_ID_LENGTH + 1), "x", "n3"))
        run(sink, replyIntent("c1", "x", null)) // no token: not one of our notifications
        run(sink, replyIntent("c1", null, "n5")) // no RemoteInput result
        assertTrue(sink.replies.isEmpty() && sink.drafts.isEmpty())
    }
}
