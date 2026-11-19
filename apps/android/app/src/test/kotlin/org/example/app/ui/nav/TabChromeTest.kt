package org.example.app.ui.nav

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlin.test.assertTrue
import org.example.app.core.chats.Contact
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.ui.calls.CallHistoryScreen
import org.example.app.ui.calls.CallHistoryViewModel
import org.example.app.ui.contacts.ContactsScreen
import org.example.app.ui.settings.SettingsScreen
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A tab's title sits directly under the status bar: no inset or bar height is applied twice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class TabChromeTest {
    @get:Rule val rule = createComposeRule()

    private val store = SampleChatStore(SampleData.make(Instant.now()))
    private val callsModel = CallHistoryViewModel(store)

    private val isTab = SemanticsMatcher("is a tab") { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }

    private fun launch(rail: Boolean, tab: MainTab, title: String) {
        rule.setContent {
            AppTheme {
                MainTabs(rail = rail) {
                    when (it) {
                        MainTab.Settings -> SettingsScreen(Contact("me", "Me", "me"), "h", 0, {}, {})
                        MainTab.Contacts -> ContactsScreen(store, null, {}, {}, {})
                        MainTab.Calls -> CallHistoryScreen(callsModel, { _, _ -> })
                        MainTab.Chats -> TabPlaceholder(it)
                    }
                }
            }
        }
        rule.onNode(isTab and hasText(title)).performClick()
        rule.waitForIdle()
        // The bar or rail repeats the label lower down; the title is the highest node with this text.
        val nodes = rule.onAllNodesWithText(title)
        val tops = (0 until nodes.fetchSemanticsNodes().size).map { nodes[it].getUnclippedBoundsInRoot().top }
        assertTrue(tops.any { it < 64.dp }, "$title title not under the status bar: $tops")
    }

    @Test fun settingsTitleIsUnderTheStatusBar() = launch(false, MainTab.Settings, "Settings")
    @Test fun contactsTitleIsUnderTheStatusBar() = launch(false, MainTab.Contacts, "Contacts")
    @Test fun callsTitleIsUnderTheStatusBar() = launch(false, MainTab.Calls, "Calls")
    @Test fun railModeCallsTitleIsUnderTheStatusBar() = launch(true, MainTab.Calls, "Calls")
}
