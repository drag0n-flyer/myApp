package org.example.app

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.core.settings.CallSettings
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.UserSettings
import org.example.app.core.settings.ServerPolicy
import org.example.app.ui.chat.ChatViewModel
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.ui.chatlist.ChatListViewModel
import org.example.app.ui.settings.NotificationDelivery
import org.example.app.ui.settings.SettingsStore
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsReachConsumersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun settingsChangeReachesTheCallEngine() {
        val graph = AppGraph(context, sampleData = true, scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined))
        val engine = graph.callEngine as org.example.app.core.calls.SimulatedCallEngine
        assertEquals(true, engine.settings.videoOffOnMobile)
        graph.settingsStore.update { it.copy(call = it.call.copy(videoOffOnMobile = false, callDataMode = ModePreference.Auto)) }
        assertEquals(false, engine.settings.videoOffOnMobile)
    }

    @Test
    fun settingsChangeReachesTheChatList() {
        val server = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
        val call = MutableStateFlow(UserSettings())
        val vm = ChatListViewModel(SampleChatStore(SampleData.make(Instant.now())), server, call, NetworkType.Wifi)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("mode_auto", vm.state.value.modeKey)
        call.value = UserSettings(call = CallSettings(callDataMode = ModePreference.Fixed(DataMode.High)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, vm.state.value.modeKey != "mode_auto")
    }

    @Test
    fun autoDownloadIsReadWhenAsked() {
        val settings = SettingsStore()
        val vm = ChatViewModel(SampleChatStore(SampleData.make(Instant.now())), "chat-riya", NetworkType.Mobile) { settings.settings.value.autoDownload }
        val before = vm.autoDownloads(org.example.app.core.settings.MediaKind.Photo)
        settings.update { it.copy(autoDownload = AutoDownloadSettings(onMobile = org.example.app.core.settings.DownloadScope.All)) }
        assertEquals(false, before)
        assertEquals(true, vm.autoDownloads(org.example.app.core.settings.MediaKind.Photo))
    }

    @Test
    fun notificationChoiceLivesInTheStore() {
        val store = SettingsStore()
        assertEquals(NotificationDelivery.Google, store.delivery.value)
        store.setDelivery(NotificationDelivery.UnifiedPush)
        assertEquals(NotificationDelivery.UnifiedPush, store.delivery.value)
    }
}
