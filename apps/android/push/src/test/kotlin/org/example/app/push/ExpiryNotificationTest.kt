package org.example.app.push

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.contracts.NotificationOutcome
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Disappearing messages and notifications (task P3): what an expiry may take away, and a notification that cannot outlive its messages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExpiryNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    @BeforeTest
    fun setUp() {
        MessageNotifier.forgetRecent()
        shadowOf(context as Application).grantPermissions("android.permission.POST_NOTIFICATIONS")
    }

    private fun notifier() = MessageNotifier(context, mutedChatIds = { emptySet() }, appLockOn = { false })
    private fun tags() = nm.activeNotifications.mapNotNull { it.tag }.toSet()
    private fun chatNote() = nm.activeNotifications.single { it.tag == "chat:c1" }.notification
    private fun lines(n: Notification) = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)!!.messages.map { it.text.toString() }

    @Test fun cancellingTheMessageNotificationLeavesAMissedCallAndANotSentNoticeAlone() {
        notifier().show(NotificationOutcome.Message("c1", "Ana", "gone"))
        notifier().showMissedCall("c1", "Ana")
        notifier().showNotSent("c1")
        notifier().cancelChatMessages("c1")
        assertEquals(setOf("call:c1", "notsent:c1"), tags())
    }

    @Test fun aChatWithMessagesLeftIsShownAgainFromThemOnlyWhenItWasShowing() {
        notifier().show(NotificationOutcome.Message("c1", "Ana", "one"))
        notifier().show(NotificationOutcome.Message("c1", "Ana", "two"))
        notifier().replaceLines("c1", "Ana", listOf("two" to 2_000L), expiresAtMs = null)
        assertEquals(listOf("two"), lines(chatNote()))
        notifier().cancelChatMessages("c1")
        notifier().replaceLines("c1", "Ana", listOf("two" to 2_000L), expiresAtMs = null)
        assertTrue("chat:c1" !in tags(), "a dismissed notification is not brought back")
    }

    @Test fun aNotificationTimesOutAtTheLatestExpiryOfItsLines() {
        val now = System.currentTimeMillis()
        notifier().show(NotificationOutcome.Message("c1", "Ana", "short", expiresAtMs = now + 60_000))
        val first = chatNote().timeoutAfter
        assertTrue(first in 1..60_000, "times out with its only message: $first")
        notifier().show(NotificationOutcome.Message("c1", "Ana", "long", expiresAtMs = now + 600_000))
        val both = chatNote().timeoutAfter
        assertTrue(both in 60_001..600_000, "the later of the two: $both")
        assertEquals(listOf("short", "long"), lines(chatNote()))
    }

    @Test fun aMessageThatNeverExpiresKeepsTheNotificationUntilItIsRead() {
        val now = System.currentTimeMillis()
        notifier().show(NotificationOutcome.Message("c1", "Ana", "plain"))
        assertEquals(0L, chatNote().timeoutAfter)
        notifier().show(NotificationOutcome.Message("c1", "Ana", "fades", expiresAtMs = now + 60_000))
        assertEquals(0L, chatNote().timeoutAfter, "one line that stays means the notification stays")
    }

    @Test fun aReplyLineOfMineDoesNotChangeTheTimeout() {
        val now = System.currentTimeMillis()
        notifier().show(NotificationOutcome.Message("c1", "Ana", "fades", expiresAtMs = now + 60_000))
        notifier().showSent("c1", "my reply")
        assertTrue(chatNote().timeoutAfter > 0)
    }
}
