package org.example.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.example.app.core.calls.CallBanner
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.calls.SimulatedCallEngine
import org.example.app.core.chats.Contact
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.MessagePreview
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.core.settings.CallSettings
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import org.example.app.core.settings.UserSettings
import org.example.app.ui.call.CallBannerView
import org.example.app.ui.call.CallControls
import org.example.app.ui.calls.CallHistoryScreen
import org.example.app.ui.calls.CallHistoryViewModel
import org.example.app.ui.chat.ChatScreen
import org.example.app.ui.chat.ChatViewModel
import org.example.app.ui.chat.Composer
import org.example.app.ui.chatinfo.ChatInfoScreen
import org.example.app.ui.chatlist.ChatListScreen
import org.example.app.ui.chatlist.ChatListViewModel
import org.example.app.ui.chatlist.ChatRow
import org.example.app.ui.chatlist.ChatRowModel
import org.example.app.ui.contacts.AddContactScreen
import org.example.app.ui.contacts.ContactsScreen
import org.example.app.ui.contacts.MessageRequestsScreen
import org.example.app.ui.allLabels
import org.example.app.ui.msg
import org.example.app.ui.onboarding.CreateAccountScreen
import org.example.app.ui.onboarding.WelcomeScreen
import org.example.app.ui.settings.PrivacyScreen
import org.example.app.ui.settings.SettingsScreen
import org.example.app.ui.settings.SettingsStore
import org.example.app.ui.storeWith
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One check for every screen: each clickable has a spoken label and a touch target of at least 48dp. */
fun ComposeContentTestRule.everyClickableHasLabel() {
    waitForIdle()
    val minPx = with(density) { 48.dp.toPx() } - 0.5f
    val nodes = onAllNodes(hasClickAction()).fetchSemanticsNodes()
    assertTrue(nodes.isNotEmpty(), "screen has no clickable nodes")
    val problems = nodes.mapNotNull { node ->
        val c = node.config
        val label = c.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            c.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(c.getOrNull(SemanticsProperties.EditableText)?.text)
        val bounds = node.touchBoundsInRoot
        when {
            label.none { it.isNotBlank() } -> "no label at $bounds"
            c.getOrNull(SemanticsProperties.Role) == null && !c.contains(SemanticsActions.SetText) -> "no role for ${label.first()}"
            bounds.width < minPx || bounds.height < minPx -> "target too small ${bounds.width}x${bounds.height}px for ${label.first()}"
            else -> null
        }
    }
    assertEquals(emptyList(), problems)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class AccessibilityTest {
    @get:Rule val rule = createComposeRule()

    private val server = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    private fun store() = SampleChatStore(SampleData.make(Instant.now()))

    private fun screen(content: @Composable () -> Unit) {
        rule.setContent { AppTheme { content() } }
        rule.everyClickableHasLabel()
    }

    @Test fun chatList() {
        val vm = ChatListViewModel(store(), server, MutableStateFlow(UserSettings()), NetworkType.Wifi)
        screen { ChatListScreen(vm, onOpenChat = {}, onOpenRequests = {}, onNewChat = {}) }
    }

    @Test fun chat() {
        val vm = ChatViewModel(store(), "chat-riya", NetworkType.Wifi) { org.example.app.core.settings.AutoDownloadSettings() }
        screen { ChatScreen(vm, myId = "me", onBack = {}, onOpenInfo = {}, onCall = {}) }
    }

    @Test fun chatInfo() = screen { ChatInfoScreen(store(), "chat-riya", onBack = {}, onCall = {}, onSearch = {}, onBlocked = {}) }

    @Test fun contacts() = screen { ContactsScreen(store(), inviteUrl = "https://h/invite/x", onOpenChat = {}, onAddContact = {}, onInvite = {}) }

    @Test fun addContact() = screen { AddContactScreen(store(), onBack = {}, onAdded = {}) }

    @Test fun messageRequests() = screen { MessageRequestsScreen(store(), onOpenChat = {}, onBack = {}) }

    @Test fun callHistory() {
        val vm = CallHistoryViewModel(store())
        screen { CallHistoryScreen(vm, onCall = { _, _ -> }) }
    }

    @Test fun settings() = screen { SettingsScreen(Contact("me", "Me", "me"), "home.example.org", linkedDevices = 2, onOpen = {}) }

    @Test fun privacy() = screen { PrivacyScreen(SettingsStore(), SampleChatStore(), onBack = {}) }

    @Test fun welcome() = screen { WelcomeScreen(canCreateAccount = true, onGetStarted = {}, onHaveAccount = {}) }

    @Test fun createAccount() = screen { CreateAccountScreen("home.example.org", null, null, false, {}, { _, _, _ -> }) }

    @Test fun callControls() {
        val engine = SimulatedCallEngine(server, CallSettings(), NetworkType.Wifi)
        engine.start("riya", false)
        engine.advance(5.0)
        val call = engine.state.value!!
        screen { CallControls(call, onSpeaker = {}, onFlip = {}, onMute = {}, onVideo = {}, onData = {}, onEnd = {}) }
    }

    // ---- font scale 2.0 ----

    @Test
    fun chatRowKeepsTimeAndBadgeVisibleForALongNameAtDoubleFontScale() {
        val row = ChatRowModel(
            "c1", "Alexandria Bartholomew Montgomery-Featherstonehaugh the Third",
            MessagePreview.Text("hello there"), Instant.now(), 12,
        )
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                AppTheme { ChatRow(row, onClick = {}) }
            }
        }
        rule.onNodeWithContentDescription("12 unread").assertIsDisplayed()
        rule.onNodeWithText("12").assertIsDisplayed()
        val timeText = rule.allLabels().firstOrNull { it.contains(Regex("\\d:\\d\\d")) }
        assertTrue(timeText != null, "time is on screen: ${rule.allLabels()}")
        val bounds = rule.onNodeWithText(timeText).fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= 0f && bounds.right <= with(rule.density) { 411.dp.toPx() }, "time inside the screen: $bounds")
    }

    // ---- RTL ----

    @Test
    fun bubblesMirrorInRtl() {
        val store = storeWith(listOf(msg("1", "me", MessageContent.Text("mine here")), msg("2", "riya", MessageContent.Text("theirs there"))))
        val vm = ChatViewModel(store, "chat-riya", NetworkType.Wifi) { org.example.app.core.settings.AutoDownloadSettings() }
        var dir by androidx.compose.runtime.mutableStateOf(LayoutDirection.Ltr)
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides dir) {
                AppTheme { ChatScreen(vm, myId = "me", onBack = {}, onOpenInfo = {}, onCall = {}) }
            }
        }
        fun left(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.left
        assertTrue(left("mine here") > left("theirs there"), "LTR: own message sits at the right")
        dir = LayoutDirection.Rtl
        rule.waitForIdle()
        assertTrue(left("mine here") < left("theirs there"), "RTL: own message sits at the left")
    }

    @Test
    fun composerMirrorsInRtl() {
        var dir by androidx.compose.runtime.mutableStateOf(LayoutDirection.Ltr)
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides dir) {
                AppTheme { Composer(enabled = true, onSend = {}, onAttach = {}, onRecordVoice = {}) }
            }
        }
        fun attachLeft() = rule.onNodeWithContentDescription("Attach").fetchSemanticsNode().boundsInRoot.left
        fun micLeft() = rule.onNodeWithContentDescription("Record voice message").fetchSemanticsNode().boundsInRoot.left
        assertTrue(attachLeft() < micLeft(), "LTR: attach first")
        dir = LayoutDirection.Rtl
        rule.waitForIdle()
        assertTrue(attachLeft() > micLeft(), "RTL: attach on the right")
    }

    @Test
    fun chatListTrailingColumnMirrorsInRtl() {
        val row = ChatRowModel("c1", "Riya", MessagePreview.Text("hi"), Instant.now(), 3)
        var dir by androidx.compose.runtime.mutableStateOf(LayoutDirection.Ltr)
        rule.setContent { CompositionLocalProvider(LocalLayoutDirection provides dir) { AppTheme { ChatRow(row, onClick = {}) } } }
        fun badgeLeft() = rule.onNodeWithContentDescription("3 unread", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        fun nameLeft() = rule.onNodeWithText("Riya", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        assertTrue(badgeLeft() > nameLeft())
        dir = LayoutDirection.Rtl
        rule.waitForIdle()
        assertTrue(badgeLeft() < nameLeft())
    }

    // ---- call banners ----

    @Test
    fun callBannersArePoliteLiveRegions() {
        rule.setContent { AppTheme { CallBannerView(CallBanner.Reconnecting) } }
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)).assertIsDisplayed()
    }
}
