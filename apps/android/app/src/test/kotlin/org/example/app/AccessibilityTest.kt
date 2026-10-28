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
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import org.example.app.core.account.DevicePlatform
import org.example.app.core.account.LinkCode
import org.example.app.core.account.LinkRequest
import org.example.app.core.account.SampleAccountService
import org.example.app.ui.onboarding.InviteCodeScreen
import org.example.app.ui.onboarding.JoinServerScreen
import org.example.app.ui.onboarding.LinkApprovalScreen
import org.example.app.ui.onboarding.LinkDeviceScreen
import org.example.app.ui.onboarding.LostDeviceScreen
import org.example.app.ui.onboarding.PasskeyScreen
import org.example.app.ui.onboarding.PermissionsScreen
import org.example.app.ui.onboarding.VerifyEmailScreen
import org.example.app.ui.settings.CallsAudioScreen
import org.example.app.ui.settings.ChatFoldersScreen
import org.example.app.ui.settings.DataStorageScreen
import org.example.app.ui.settings.LinkedDevicesScreen
import org.example.app.ui.settings.NotificationDelivery
import org.example.app.ui.settings.NotificationsScreen
import org.example.app.ui.settings.ServerScreen
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

/** Nodes Material itself adds to a bottom sheet. We cannot give them a role. */
private val materialOwnedLabels = setOf("Close sheet", "Drag handle")

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
            c.getOrNull(SemanticsProperties.Role) == null && !c.contains(SemanticsActions.SetText) && label.first() !in materialOwnedLabels -> "no role for ${label.first()}"
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

    // ---- the rest of the screens ----

    private val settingsStore = SettingsStore()

    private fun callVm(incoming: Boolean = false): org.example.app.ui.call.CallViewModel {
        val engine = SimulatedCallEngine(server, CallSettings(), NetworkType.Wifi)
        if (incoming) engine.simulateIncoming("riya", video = false) else { engine.start("riya", false); engine.advance(5.0) }
        return org.example.app.ui.call.CallViewModel(engine, store(), server, CallSettings(), simulator = engine, showSimulator = true)
    }

    private fun withLifecycle(content: @Composable () -> Unit): @Composable () -> Unit = {
        CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides org.example.app.ui.call.TestLifecycleOwner()) { content() }
    }

    @Test fun chatFolders() = screen { ChatFoldersScreen(store(), onBack = {}) }

    @Test fun linkedDevices() {
        rule.setContent { AppTheme { LinkedDevicesScreen(SampleAccountService(), onBack = {}) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("iPhone").fetchSemanticsNodes().isNotEmpty() }
        rule.everyClickableHasLabel()
    }

    @Test fun dataStorage() = screen { DataStorageScreen(settingsStore, server, onBack = {}) }

    @Test fun callsAudio() = screen { CallsAudioScreen(settingsStore, onBack = {}) }

    @Test fun serverPage() = screen { ServerScreen("home.example.org", onBack = {}) }

    @Test fun notifications() = screen { NotificationsScreen(NotificationDelivery.Google, {}, canUseFullScreenIntent = true, onOpenFullScreenSettings = {}, onBack = {}) }

    @Test fun notificationsWithTheLockScreenRow() = screen { NotificationsScreen(NotificationDelivery.UnifiedPush, {}, canUseFullScreenIntent = false, onOpenFullScreenSettings = {}, onBack = {}) }

    @Test fun incomingCall() { val vm = callVm(incoming = true); screen(withLifecycle { org.example.app.ui.call.IncomingCallScreen(vm) }) }

    @Test fun callScreen() { val vm = callVm(); screen(withLifecycle { org.example.app.ui.call.CallScreen(vm, ticking = false) }) }

    @Test fun welcomeWithoutAccountCreation() = screen { WelcomeScreen(canCreateAccount = false, onGetStarted = {}, onHaveAccount = {}) }

    @Test fun joinServer() = screen { JoinServerScreen(null, {}, { true }, {}, scanner = { Text("scanner") }) }

    @Test fun inviteCode() = screen { InviteCodeScreen(null, false) {} }

    @Test fun verifyEmail() = screen { VerifyEmailScreen(null, false) {} }

    @Test fun passkey() = screen { PasskeyScreen(true, null, false, {}, {}) }

    @Test fun passkeyWithoutSupport() = screen { PasskeyScreen(false, null, false, {}, {}) }

    @Test fun permissions() = screen { PermissionsScreen(onRequest = {}, onDone = {}) }

    @Test fun lostDevice() = screen { LostDeviceScreen(false, null, false, {}, {}, { _, _ -> }) }

    @Test fun lostDeviceCodeSent() = screen { LostDeviceScreen(true, null, false, {}, {}, { _, _ -> }) }

    @Test fun linkDevice() = screen {
        LinkDeviceScreen("home.example.org", LinkCode("https://home.example.org/link/a#b", Instant.now()), false, null, canCreateAccount = true, onNewCode = {}, onChangeServer = {}, onNoOldDevice = {})
    }

    @Test fun linkDeviceExpired() = screen {
        LinkDeviceScreen("home.example.org", LinkCode("https://home.example.org/link/a#b", Instant.now()), true, null, canCreateAccount = true, onNewCode = {}, onChangeServer = {}, onNoOldDevice = {})
    }

    @Test fun linkApproval() = screen {
        LinkApprovalScreen(LinkRequest("https://home.example.org/link/x#y", "New Pixel", DevicePlatform.Android, Instant.now()), null, false, {}, {})
    }

    // ---- sheets, menus and dialogs, opened the way a person opens them ----

    private fun open(content: @Composable () -> Unit, opener: ComposeContentTestRule.() -> Unit) {
        rule.setContent { AppTheme { content() } }
        rule.waitForIdle()
        rule.opener()
        rule.everyClickableHasLabel()
    }

    @Test fun chatListMenu() {
        val vm = ChatListViewModel(store(), server, MutableStateFlow(UserSettings()), NetworkType.Wifi)
        open({ ChatListScreen(vm, onOpenChat = {}, onOpenRequests = {}, onNewChat = {}) }) {
            onNodeWithContentDescription("More options").performClick()
            onNodeWithText("Mark all as read").assertIsDisplayed()
        }
    }

    @Test fun attachSheet() = open({ Composer(enabled = true, onSend = {}, onAttach = {}, onRecordVoice = {}) }) {
        onNodeWithContentDescription("Attach").performClick()
        onNodeWithText("Photo").assertIsDisplayed()
    }

    @Test fun verifySheet() = open({ ChatInfoScreen(store(), "chat-riya", onBack = {}, onCall = {}, onSearch = {}, onBlocked = {}) }) {
        onNodeWithText("Verify").performClick()
        onNodeWithText("Mark as verified").assertIsDisplayed()
    }

    @Test fun blockDialog() = open({ ChatInfoScreen(store(), "chat-riya", onBack = {}, onCall = {}, onSearch = {}, onBlocked = {}) }) {
        onNodeWithText("Block Riya").performClick()
        onNodeWithText("Block this person?").assertIsDisplayed()
    }

    @Test fun reportDialog() = open({ ChatInfoScreen(store(), "chat-riya", onBack = {}, onCall = {}, onSearch = {}, onBlocked = {}) }) {
        onNodeWithText("Report").performClick()
        onNodeWithText("Report this chat?", substring = true).assertIsDisplayed()
    }

    @Test fun renameFolderForm() = screen { org.example.app.ui.settings.RenameFolderForm("Work", onSave = {}, onCancel = {}) }

    @Test fun newFolderForm() = screen { org.example.app.ui.settings.NewFolderForm(listOf("c1" to "Riya", "c2" to "Sam"), onCreate = { _, _ -> }, onCancel = {}) }

    @Test fun deleteFolderDialog() = open({ ChatFoldersScreen(store(), onBack = {}) }) {
        onNodeWithContentDescription("Delete Work").performClick()
        onNodeWithText("Delete this folder?").assertIsDisplayed()
    }

    @Test fun removeDeviceDialog() {
        rule.setContent { AppTheme { LinkedDevicesScreen(SampleAccountService(), onBack = {}) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("iPhone").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Remove iPhone").performClick()
        rule.onNodeWithText("Remove this device?").assertIsDisplayed()
        rule.everyClickableHasLabel()
    }

    @Test fun choiceDialog() = open({ DataStorageScreen(settingsStore, server, onBack = {}) }) {
        onNodeWithText("Call data mode").performClick()
        onAllNodesWithText("Cancel").assertCountEquals(1)
    }

    @Test fun dataModeSheet() {
        val options = org.example.app.core.calls.DataModePolicy.sheetOptions(server, CallSettings(), NetworkType.Wifi, null)
        screen { org.example.app.ui.call.DataModeSheetContent(options, onSelect = {}) }
    }

    @Test fun dataModeSheetFromTheCallScreen() {
        val vm = callVm()
        open(withLifecycle { org.example.app.ui.call.CallScreen(vm, ticking = false) }) {
            onNodeWithContentDescription("Data").performClick()
            onNodeWithText("Data for this call").assertIsDisplayed()
        }
    }

    @Test fun simulateMenu() {
        val vm = callVm()
        open(withLifecycle { org.example.app.ui.call.CallScreen(vm, ticking = false) }) {
            onNodeWithContentDescription("Simulate network").performClick()
            onNodeWithText("Weak").assertIsDisplayed()
        }
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
        // The merged tree folds everything into the row, so look at the parts themselves.
        val rowBounds = rule.onNode(hasClickAction()).fetchSemanticsNode().boundsInRoot
        val badge = rule.onNodeWithContentDescription("12 unread", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(badge.width > 0f && badge.height > 0f, "badge has a size: $badge")
        assertTrue(badge.left >= rowBounds.left && badge.right <= rowBounds.right, "badge inside the row: $badge in $rowBounds")
        val timeText = rule.allLabels().firstOrNull { it.contains(Regex("\\d:\\d\\d")) }
        assertTrue(timeText != null, "time is on screen: ${rule.allLabels()}")
        val time = rule.onNodeWithText(timeText, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(time.width > 0f && time.height > 0f, "time has a size: $time")
        assertTrue(time.left >= rowBounds.left && time.right <= rowBounds.right, "time inside the row: $time in $rowBounds")
        val name = rule.onNodeWithText(row.name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(name.width > 0f, "name keeps some room: $name")
        assertTrue(name.right <= time.left || name.left >= time.right, "time does not overlap the name: $name vs $time")
        assertTrue(badge.top >= time.bottom - 0.5f, "badge sits under the time: $badge vs $time")
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
