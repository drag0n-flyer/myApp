package org.example.app.live

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.contracts.NotificationOutcome
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.push.MessageNotifier
import org.example.app.push.NotificationChannels
import org.example.app.push.PushRuntime
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The mute switch reaches the notifier through the real `PushRuntime.mutedChatIds` hook that `PushWiring` installs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MutedChatsReachNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private var current: ChatStore = EmptyChatStore

    @BeforeTest
    fun setUp() {
        shadowOf(context as Application).grantPermissions("android.permission.POST_NOTIFICATIONS")
        PushWiring.wireSwitches { current }
    }

    @AfterTest
    fun tearDown() {
        PushRuntime.mutedChatIds = { emptySet() }
    }

    private fun channelOf(chatId: String) =
        nm.activeNotifications.first { it.tag == "chat:$chatId" }.notification.channelId

    @Test
    fun aMutedChatGoesToTheMutedChannelAndAnotherDoesNot() {
        val store = SampleChatStore(SampleData.make(Instant.now()))
        current = store
        store.setMuted("chat-sam", true)
        MessageNotifier(context).show(NotificationOutcome.Message("chat-sam", "Sam", "muted one"))
        MessageNotifier(context).show(NotificationOutcome.Message("chat-riya", "Riya", "loud one"))
        assertEquals(NotificationChannels.MUTED, channelOf("chat-sam"))
        assertTrue(channelOf("chat-riya") != NotificationChannels.MUTED)
    }

    @Test
    fun unmutingMakesTheNextNotificationLoudAgain() {
        val store = SampleChatStore(SampleData.make(Instant.now()))
        current = store
        store.setMuted("chat-arjun", true)
        assertEquals(setOf("chat-arjun"), PushRuntime.mutedChatIds())
        store.setMuted("chat-arjun", false)
        assertTrue(PushRuntime.mutedChatIds().isEmpty())
    }

    @Test
    fun coldStartWithNoSessionYetReadsAnEmptySetAtOnceAndTheOpenedStoreAfterwards() {
        // No session open: the hook answers immediately with nothing muted (it never opens or waits for a session).
        assertTrue(PushRuntime.mutedChatIds().isEmpty())
        // The push path opens the session before it posts; the store has loaded its mute list by then.
        val opened = SampleChatStore(SampleData.make(Instant.now()))
        opened.setMuted("chat-neha-cold", true)
        opened.setMuted("chat-sam", true)
        current = opened
        MessageNotifier(context).show(NotificationOutcome.Message("chat-sam", "Sam", "first after cold start"))
        assertEquals(NotificationChannels.MUTED, channelOf("chat-sam"), "the first notification of a muted chat is already quiet")
    }
}
