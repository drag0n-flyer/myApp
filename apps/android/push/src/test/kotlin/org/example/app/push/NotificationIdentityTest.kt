package org.example.app.push

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.NotificationOutcome
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Aa" and "BB" have the same `String.hashCode`. Two chats whose ids collide must still get their own PendingIntents,
 * or the second notification would rewrite the first one's chat id and a reply would go to the wrong chat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationIdentityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val notifier get() = MessageNotifier(context, mutedChatIds = { emptySet() }, appLockOn = { false })

    @BeforeTest
    fun setUp() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        shadowOf(context as Application).grantPermissions("android.permission.POST_NOTIFICATIONS")
        val info = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = context.packageName
                name = "org.example.app.FakeLauncher"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), info,
        )
    }

    private fun chat(id: String): Notification = nm.activeNotifications.first { it.tag == "chat:$id" }.notification

    private fun twoChats() {
        notifier.show(NotificationOutcome.Message("Aa", "Ana", "hi"))
        notifier.show(NotificationOutcome.Message("BB", "Bo", "yo"))
    }

    private fun idIn(pi: android.app.PendingIntent?): String? = shadowOf(pi).savedIntent.getStringExtra(MessageNotifier.EXTRA_CHAT_ID)

    @Test
    fun eachChatsReplyButtonDeliversItsOwnChatId() {
        twoChats()
        for (id in listOf("Aa", "BB")) {
            val reply = chat(id).actions.first { it.title == "Reply" }
            assertEquals(id, idIn(reply.actionIntent), "reply for $id")
        }
        val a = chat("Aa").actions.first { it.title == "Reply" }.actionIntent
        val b = chat("BB").actions.first { it.title == "Reply" }.actionIntent
        assertNotEquals(a, b)
    }

    @Test
    fun eachChatsMarkAsReadButtonDeliversItsOwnChatId() {
        twoChats()
        for (id in listOf("Aa", "BB")) {
            val read = chat(id).actions.first { it.title == "Mark as read" }
            assertEquals(id, idIn(read.actionIntent), "mark as read for $id")
        }
    }

    @Test
    fun eachChatsTapOpensItsOwnChat() {
        twoChats()
        for (id in listOf("Aa", "BB")) assertEquals(id, idIn(chat(id).contentIntent), "tap for $id")
    }

    @Test
    fun theNotSentNoticeOpensItsOwnChat() = runTest {
        twoChats()
        notifier.showNotSent("Aa")
        notifier.showNotSent("BB")
        for (id in listOf("Aa", "BB")) {
            val n = nm.activeNotifications.first { it.tag == "notsent:$id" }.notification
            assertEquals(id, idIn(n.contentIntent), "not sent for $id")
        }
        // and the chat's own tap was not disturbed by it
        assertTrue(listOf("Aa", "BB").all { idIn(chat(it).contentIntent) == it })
    }
}
