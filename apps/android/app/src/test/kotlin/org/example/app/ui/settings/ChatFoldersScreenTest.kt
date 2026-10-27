package org.example.app.ui.settings

import java.time.Instant
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
class ChatFoldersScreenTest {
    @get:Rule val rule = createComposeRule()
    private val store = SampleChatStore(SampleData.make(Instant.now()))

    @Test
    fun listsFixedAndCustomFolders() {
        rule.setContent { AppTheme { ChatFoldersScreen(store, onBack = {}) } }
        rule.onNodeWithText("All").assertIsDisplayed()
        rule.onNodeWithText("Unread").assertIsDisplayed()
        rule.onNodeWithText("Work").assertIsDisplayed()
        rule.onNodeWithText("All and Unread are always there.").assertIsDisplayed()
    }

    @Test
    fun deleteFolder() {
        rule.setContent { AppTheme { ChatFoldersScreen(store, onBack = {}) } }
        rule.onNodeWithContentDescription("Delete Work").performClick()
        rule.waitForIdle()
        assertTrue(store.folders.value.none { it.name == "Work" })
    }
}
