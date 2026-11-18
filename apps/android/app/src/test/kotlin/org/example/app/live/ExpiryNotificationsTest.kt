package org.example.app.live

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.contracts.NotificationOutcome
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.push.MessageNotifier
import org.example.app.ui.storeWith
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExpiryNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    private val priorLock = org.example.app.push.PushRuntime.appLockOn

    @Before fun setUp() {
        shadowOf(context as Application).grantPermissions("android.permission.POST_NOTIFICATIONS")
        org.example.app.push.PushRuntime.appLockOn = { false }
    }

    @org.junit.After fun tearDown() { org.example.app.push.PushRuntime.appLockOn = priorLock }

    private fun incoming(id: String, text: String, at: Long) = Message(id, "riya", Instant.ofEpochMilli(at), MessageContent.Text(text), DeliveryStatus.Delivered, null, emptyList())

    private fun lines() = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
        nm.activeNotifications.single { it.tag == "chat:chat-riya" }.notification,
    )!!.messages.map { it.text.toString() }

    private fun showTwo() {
        val n = MessageNotifier(context)
        n.show(NotificationOutcome.Message("chat-riya", "Riya", "one"))
        n.show(NotificationOutcome.Message("chat-riya", "Riya", "two"))
        n.showMissedCall("chat-riya", "Riya")
    }

    @Test fun unreadLeftShowsOnlyThoseAndKeepsTheCallNotice() {
        MessageNotifier.forgetRecent()
        val store = storeWith(listOf(incoming("1", "one", 1_000), incoming("2", "two", 2_000)))
        showTwo()
        ExpiryNotifications.apply(context, store, mapOf("chat-riya" to 1))
        assertEquals(listOf("two"), lines())
        assertTrue(nm.activeNotifications.any { it.tag == "call:chat-riya" })
    }

    @Test fun nothingUnreadLeftCancelsTheMessageNoticeButNotTheMissedCall() {
        MessageNotifier.forgetRecent()
        val store = storeWith(emptyList())
        showTwo()
        ExpiryNotifications.apply(context, store, mapOf("chat-riya" to 0))
        assertEquals(setOf("call:chat-riya"), nm.activeNotifications.mapNotNull { it.tag }.toSet())
    }

    @Test fun aChatThatLostNothingIncomingIsLeftAlone() {
        MessageNotifier.forgetRecent()
        val store = storeWith(listOf(incoming("1", "one", 1_000)))
        showTwo()
        ExpiryNotifications.apply(context, store, emptyMap()) // my own message expired: the sweeper reports no chat
        assertEquals(listOf("one", "two"), lines())
    }

    @Test fun aContactWithoutADisplayNameIsTitledByItsUsername() {
        MessageNotifier.forgetRecent()
        val inner = storeWith(listOf(incoming("1", "one", 1_000), incoming("2", "two", 2_000)))
        val store = object : org.example.app.core.chats.ChatStore by inner {
            override fun contact(id: String) = org.example.app.core.chats.Contact("riya", "", "riya_u")
        }
        showTwo()
        ExpiryNotifications.apply(context, store, mapOf("chat-riya" to 1))
        assertEquals("riya_u", nm.activeNotifications.single { it.tag == "chat:chat-riya" }.notification.extras.getString("android.title"))
    }
}
