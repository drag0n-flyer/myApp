package org.example.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import java.time.Instant
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.core.people.NoWitnessStore
import org.example.app.core.people.PeopleSignals
import org.example.app.core.people.ScriptedSignals
import org.example.app.core.people.Signal
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import org.example.app.core.settings.UserSettings
import org.example.app.ui.chat.ChatScreen
import org.example.app.ui.chat.ChatViewModel
import org.example.app.ui.chatlist.ChatListScreen
import org.example.app.ui.chatlist.ChatListViewModel
import org.example.app.ui.settings.PrivacyScreen
import org.example.app.ui.settings.SettingsStore
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Typing and presence on screen (design 6.1), sample data with a scripted channel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class SignalsUiTest {
    @get:Rule val rule = createComposeRule()

    private val server = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    private val store = SampleChatStore(SampleData.make(Instant.now()))
    private val channel = ScriptedSignals()
    private val settings = MutableStateFlow(UserSettings())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val signals = PeopleSignals(
        scope = scope, channel = channel,
        typingEnabled = { settings.value.typingIndicator }, presenceEnabled = { settings.value.onlineAndLastSeen },
        blocked = { id -> store.chat(id)?.contactId?.let { it in store.blockedIds.value } ?: true },
        presenceTargets = { store.chats.value.map { it.id } },
        witnesses = NoWitnessStore, nowMs = System::currentTimeMillis,
    ).also { it.watchMessages(store.chats, store.me.id) }

    @Test fun theSampleChatShowsTypingInTheListAndTheHeaderThenTheMessage() {
        val list = ChatListViewModel(store, server, settings, NetworkType.Wifi, signals = signals)
        val chat = ChatViewModel(store, "chat-riya", NetworkType.Wifi, signals = signals) { AutoDownloadSettings() }
        var showChat by mutableStateOf(false)
        rule.setContent {
            AppTheme {
                if (showChat) ChatScreen(chat, myId = "me", onBack = {}, onOpenInfo = {}, onCall = null)
                else ChatListScreen(list, onOpenChat = {}, onOpenRequests = {}, onNewChat = {}, onSearch = {})
            }
        }
        rule.onAllNodesWithText("typing…").assertCountEquals(0)

        channel.emit("chat-riya", Signal.Typing(true))
        rule.waitForIdle()
        rule.onNodeWithText("typing…").assertIsDisplayed() // the Riya row, in place of the preview

        rule.runOnIdle { showChat = true }
        rule.waitForIdle()
        rule.onNodeWithText("typing…").assertIsDisplayed() // under the name in the header

        store.receive(MessageContent.Text("On my way"), store.contact("riya")!!)
        rule.waitForIdle()
        rule.onAllNodesWithText("typing…").assertCountEquals(0)
        rule.onNodeWithText("On my way").assertIsDisplayed()
    }

    @Test fun onlineShowsUnderTheNameAndTheIndicatorSwitchHidesTyping() {
        val chat = ChatViewModel(store, "chat-riya", NetworkType.Wifi, signals = signals) { AutoDownloadSettings() }
        rule.setContent { AppTheme { ChatScreen(chat, myId = "me", onBack = {}, onOpenInfo = {}, onCall = null) } }
        channel.emit("chat-riya", Signal.Presence(true))
        rule.waitForIdle()
        rule.onNodeWithText("online").assertIsDisplayed()

        settings.value = settings.value.copy(typingIndicator = false)
        channel.emit("chat-riya", Signal.Typing(true))
        rule.waitForIdle()
        rule.onAllNodesWithText("typing…").assertCountEquals(0)
    }

    @Test fun typingInTheComposerSendsOnAndSendingSendsOff() {
        val chat = ChatViewModel(store, "chat-riya", NetworkType.Wifi, signals = signals) { AutoDownloadSettings() }
        rule.setContent { AppTheme { ChatScreen(chat, myId = "me", onBack = {}, onOpenInfo = {}, onCall = null) } }
        rule.onNode(hasSetTextAction()).performTextInput("hi")
        rule.waitForIdle()
        assertEquals(listOf("chat-riya" to Signal.Typing(true)), channel.sent)
        rule.runOnIdle { chat.send("hi") }
        rule.waitForIdle()
        assertEquals(listOf("chat-riya" to Signal.Typing(true), "chat-riya" to Signal.Typing(false)), channel.sent)
    }

    @Test fun aBlockedContactShowsNothing() {
        val chat = ChatViewModel(store, "chat-riya", NetworkType.Wifi, signals = signals) { AutoDownloadSettings() }
        rule.setContent { AppTheme { ChatScreen(chat, myId = "me", onBack = {}, onOpenInfo = {}, onCall = null) } }
        store.block("riya")
        channel.emit("chat-riya", Signal.Typing(true))
        chat.typed(isEmpty = false) // typing after the block
        rule.waitForIdle()
        rule.onAllNodesWithText("typing…").assertCountEquals(0)
        assertEquals(emptyList(), channel.sent)
    }

    @Test fun privacyHasBothSwitchesOnByDefaultWithTheirFooters() {
        val store = SettingsStore()
        val lock = org.example.app.ui.lock.AppLock({ store.settings.value })
        rule.setContent {
            AppTheme { PrivacyScreen(store, SampleChatStore(), lock, org.example.app.ui.lock.SampleDeviceAuth(), saveFindByEmail = {}, loadFindByEmail = { null }, onBack = {}) }
        }
        rule.onNodeWithText("Typing indicator").assertIsDisplayed()
        rule.onNodeWithText("Online and last seen").assertIsDisplayed()
        rule.onNodeWithText("Others can see when you are typing. If you turn this off, you won't see it for others either.").assertIsDisplayed()
        rule.onNodeWithText("Others can see when you were last in the app. If you turn this off, you won't see it for others either.").assertIsDisplayed()
        rule.onNodeWithText("Typing indicator").performClick()
        rule.waitForIdle()
        assertEquals(false, store.settings.value.typingIndicator)
        assertEquals(true, store.settings.value.onlineAndLastSeen)
    }

    @Test fun aSavedDraftRestoredWhenTheChatOpensSendsNoTyping() {
        store.setDraft("chat-riya", "half written")
        val chat = ChatViewModel(store, "chat-riya", NetworkType.Wifi, signals = signals) { AutoDownloadSettings() }
        rule.setContent { AppTheme { ChatScreen(chat, myId = "me", onBack = {}, onOpenInfo = {}, onCall = null) } }
        rule.waitForIdle()
        rule.onNodeWithText("half written").assertIsDisplayed()
        assertEquals(emptyList(), channel.sent)
    }

    @Test fun openingAndClosingAChatSendsNoFrame() {
        val chat = ChatViewModel(store, "chat-riya", NetworkType.Wifi, signals = signals) { AutoDownloadSettings() }
        var open by mutableStateOf(true)
        rule.setContent { AppTheme { if (open) ChatScreen(chat, myId = "me", onBack = {}, onOpenInfo = {}, onCall = null) } }
        rule.waitForIdle()
        rule.runOnIdle { open = false }
        rule.waitForIdle()
        assertEquals(emptyList(), channel.sent)
    }

    @Test fun aSwitchLeftOffSurvivesARestartAndNothingIsSent() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("signals-restart-test", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        SettingsStore(prefs).update { it.copy(typingIndicator = false, onlineAndLastSeen = false) }
        val relaunched = SettingsStore(prefs)
        val model = PeopleSignals(
            scope = scope, channel = channel,
            typingEnabled = { relaunched.settings.value.typingIndicator }, presenceEnabled = { relaunched.settings.value.onlineAndLastSeen },
            blocked = { false }, presenceTargets = { listOf("chat-riya") }, witnesses = NoWitnessStore, nowMs = System::currentTimeMillis,
        )
        model.composerChanged("chat-riya", isEmpty = false)
        model.setForeground(true)
        model.setForeground(false)
        assertEquals(emptyList(), channel.sent)
    }

    @Test fun anEditBeganCancelledAndFinishedSendsNoTyping() {
        val own = org.example.app.core.chats.Message(
            "m1", "me", Instant.now().minusSeconds(60), MessageContent.Text("typo"), org.example.app.core.chats.DeliveryStatus.Sent, null, emptyList(),
        )
        val data = SampleData.make(Instant.now())
        val editStore = SampleChatStore(data.copy(chats = listOf(org.example.app.core.chats.Chat("chat-riya", "riya", org.example.app.core.chats.ChatEncryption.EndToEnd, emptySet(), 0, listOf(own), false))))
        val chat = ChatViewModel(editStore, "chat-riya", NetworkType.Wifi, signals = signals) { AutoDownloadSettings() }
        rule.setContent { AppTheme { ChatScreen(chat, myId = "me", onBack = {}, onOpenInfo = {}, onCall = null) } }
        fun beginEdit() {
            rule.onNodeWithText("typo").performTouchInput { longClick() }
            rule.onNodeWithText("Edit").performClick()
            rule.onNodeWithText("Editing").assertIsDisplayed()
        }
        beginEdit()
        rule.onNodeWithContentDescription("Close").performClick() // cancelled
        rule.waitForIdle()
        beginEdit()
        rule.onNode(hasSetTextAction()).performTextInput("!")
        rule.onNodeWithContentDescription("Send").performClick() // finished
        rule.waitForIdle()
        assertEquals(emptyList(), channel.sent)
    }

    @Test fun leavingTheChatWhileTypingSendsOffAtOnce() {
        val chat = ChatViewModel(store, "chat-riya", NetworkType.Wifi, signals = signals) { AutoDownloadSettings() }
        var open by mutableStateOf(true)
        rule.setContent { AppTheme { if (open) ChatScreen(chat, myId = "me", onBack = {}, onOpenInfo = {}, onCall = null) } }
        rule.onNode(hasSetTextAction()).performTextInput("hi")
        rule.waitForIdle()
        rule.runOnIdle { open = false }
        rule.waitForIdle()
        assertEquals(listOf("chat-riya" to Signal.Typing(true), "chat-riya" to Signal.Typing(false)), channel.sent)
    }

    @Test fun switchingAPrivacySwitchOffClearsALineAlreadyShown() {
        val live = MutableStateFlow(UserSettings())
        val built = org.example.app.live.buildSignals(scope, channel, store, live, NoWitnessStore)
        channel.emit("chat-riya", Signal.Typing(true))
        channel.emit("chat-riya", Signal.Presence(true))
        assertEquals(true, built.views.value["chat-riya"]?.typing)
        live.value = live.value.copy(typingIndicator = false)
        assertEquals(false, built.views.value["chat-riya"]?.typing == true)
        assertEquals(true, built.views.value["chat-riya"]?.presence == org.example.app.core.people.PresenceLine.Result.Online)
        live.value = live.value.copy(onlineAndLastSeen = false)
        assertEquals(null, built.views.value["chat-riya"])
    }

    @Test fun blockingAContactClearsALineAlreadyShown() {
        val built = org.example.app.live.buildSignals(scope, channel, store, MutableStateFlow(UserSettings()), NoWitnessStore)
        channel.emit("chat-riya", Signal.Typing(true))
        assertEquals(true, built.views.value["chat-riya"]?.typing)
        store.block("riya")
        assertEquals(null, built.views.value["chat-riya"])
    }

    @Test fun theRealBlockedRuleDropsRequestAndUnknownChats() {
        val built = org.example.app.live.buildSignals(scope, channel, store, MutableStateFlow(UserSettings()), NoWitnessStore)
        val request = store.requests.value.first().id
        assertEquals(true, store.chat(request)?.isRequest)
        for (id in listOf(request, "chat-nobody")) {
            channel.emit(id, Signal.Typing(true))
            channel.emit(id, Signal.Presence(true))
            built.composerChanged(id, isEmpty = false)
            built.setForeground(true)
            assertEquals(null, built.views.value[id], id)
        }
        // only real, unblocked chats get `online`
        assertEquals(true, channel.sent.none { it.first == request || it.first == "chat-nobody" })
        built.setForeground(false)
    }
}
