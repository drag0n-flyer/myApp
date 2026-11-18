package org.example.app.push

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.app.contracts.NotificationOutcome
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessageNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var muted: Set<String> = emptySet()
    private var locked = false
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    @BeforeTest
    fun setUp() {
        shadowOf(context as Application).grantPermissions("android.permission.POST_NOTIFICATIONS")
    }

    private fun notifier() = MessageNotifier(context, mutedChatIds = { muted }, appLockOn = { locked })

    private fun chatNotifications(): List<StatusBarNotification> = nm.activeNotifications.filter { it.tag?.startsWith("chat:") == true }

    private fun summary(): StatusBarNotification? = nm.activeNotifications.firstOrNull { it.tag == "summary" }

    private fun lines(n: Notification): List<String> =
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)!!.messages.map { it.text.toString() }

    private fun say(chatId: String, who: String, text: String) =
        notifier().show(NotificationOutcome.Message(chatId, who, text))

    @Test
    fun oneChatKeepsOneNotificationWithAllItsLines() {
        say("c1", "Ana", "one")
        say("c1", "Ana", "two")
        val only = chatNotifications().single()
        assertEquals(listOf("one", "two"), lines(only.notification))
        assertEquals("Ana", only.notification.extras.getString("android.title"))
        assertNull(summary(), "one chat needs no summary")
    }

    @Test
    fun onlyTheNewestSixLinesAreKept() {
        for (i in 1..9) say("c1", "Ana", "m$i")
        assertEquals((4..9).map { "m$it" }, lines(chatNotifications().single().notification))
    }

    @Test
    fun twoChatsAreGroupedUnderASummaryThatCountsUnmutedChats() {
        say("c1", "Ana", "hi")
        say("c2", "Bo", "yo")
        assertEquals(2, chatNotifications().size)
        val s = checkNotNull(summary())
        assertTrue(s.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals(2, s.notification.number)
        val groups = (chatNotifications() + s).map { it.notification.group }.toSet()
        assertEquals(1, groups.size)
        assertNotNull(groups.single())
    }

    @Test
    fun mutedChatIsQuietOnTheMutedChannelAndNotCountedInTheBadge() {
        muted = setOf("c2")
        say("c1", "Ana", "hi")
        say("c2", "Bo", "yo")
        val quiet = chatNotifications().single { it.tag == "chat:c2" }
        assertEquals("muted", quiet.notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel("muted").importance)
        assertEquals("Muted chats", nm.getNotificationChannel("muted").name.toString())
        assertEquals("messages", chatNotifications().single { it.tag == "chat:c1" }.notification.channelId)
        assertEquals(1, checkNotNull(summary()).notification.number)
        assertEquals(listOf("yo"), lines(quiet.notification), "muted still shows its text")
    }

    @Test
    fun aMissedCallIsNeverMuted() {
        muted = setOf("c1")
        notifier().showMissedCall("c1", "Ana")
        val n = nm.activeNotifications.single { it.tag == "call:c1" }
        assertEquals("messages", n.notification.channelId)
    }

    @Test
    fun appLockHidesSenderAndTextAndForgetsEarlierLines() {
        say("c1", "Ana", "before the lock")
        locked = true
        say("c1", "Ana", "secret")
        val n = chatNotifications().single().notification
        assertEquals(listOf("New message"), lines(n))
        assertEquals("New message", n.extras.getString("android.text"))
        val shown = n.extras.keySet().mapNotNull { n.extras.get(it)?.toString() }.joinToString("|")
        assertTrue(!shown.contains("Ana") && !shown.contains("secret") && !shown.contains("before the lock"), shown)
    }

    @Test
    fun readingAChatRemovesItsNotificationAndTheSummaryWhenOneIsLeft() {
        say("c1", "Ana", "hi")
        say("c2", "Bo", "yo")
        notifier().cancelChat("c1")
        assertEquals(listOf("chat:c2"), chatNotifications().map { it.tag })
        assertNull(summary())
        notifier().cancelChat("c2")
        assertTrue(chatNotifications().isEmpty())
    }

    @Test
    fun readingAChatAlsoRemovesItsMissedCallNotice() {
        say("c1", "Ana", "hi")
        notifier().showMissedCall("c1", "Ana")
        notifier().cancelChat("c1")
        assertTrue(nm.activeNotifications.isEmpty())
    }
}
