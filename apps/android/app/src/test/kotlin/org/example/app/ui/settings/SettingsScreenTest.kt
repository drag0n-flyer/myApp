package org.example.app.ui.settings

import org.example.app.core.chats.Contact
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.ui.allLabels
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun listsEveryPageAndOpensIt() {
        val opened = mutableListOf<SettingsPage>()
        rule.setContent {
            AppTheme { SettingsScreen(Contact("me", "Me", "me"), "home.example.org", linkedDevices = 2, onOpen = { opened += it }) }
        }
        for (title in listOf("Server", "Linked devices", "Privacy", "Data & Storage", "Notifications", "Chat folders", "Calls & Audio")) {
            rule.onNodeWithText(title).assertIsDisplayed()
        }
        rule.onNodeWithText("Privacy").performClick()
        assertEquals(listOf(SettingsPage.Privacy), opened)
    }
}
