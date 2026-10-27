package org.example.app.ui.nav

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainTabsTest {
    @get:Rule val rule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val isTab = SemanticsMatcher("is a tab") { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }

    private fun launch() = rule.setContent { AppTheme { MainTabs() } }

    private fun label(tab: MainTab) = context.getString(tab.label)

    @Test
    fun tabsInTelegramOrder() {
        launch()
        val tabs = rule.onAllNodes(isTab).fetchSemanticsNodes()
        val names = tabs.map { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
        assertEquals(listOf("Contacts", "Calls", "Chats", "Settings"), names)
        rule.onNode(isTab and hasText("Chats")).assertIsSelected()
    }

    @Test
    fun everyClickableHasLabel() {
        launch()
        for (tab in MainTab.entries) {
            rule.onNode(isTab and hasText(label(tab))).performClick()
            rule.waitForIdle()
            val unlabeled = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
                val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                text.isEmpty() && description.isEmpty()
            }
            assertTrue(unlabeled.isEmpty(), "unlabelled clickable on ${label(tab)}: $unlabeled")
        }
    }
}
