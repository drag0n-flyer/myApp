package org.example.app.ui.chatlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.example.app.core.chats.MessagePreview
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class GroupRowPreviewTest {
    @get:Rule val rule = createComposeRule()

    private fun show(row: ChatRowModel) = rule.setContent { AppTheme { ChatRow(row, onClick = {}) } }

    private fun base() = ChatRowModel("g:1", "Weekend plans", MessagePreview.Text("see you at 6"), null, 0)

    @Test fun aMemberMessageShowsNameColonText() {
        show(base().copy(previewSender = "Riya"))
        rule.onNodeWithText("Riya: see you at 6").assertIsDisplayed()
    }

    @Test fun myMessageShowsYouColonText() {
        show(base().copy(previewFromMe = true))
        rule.onNodeWithText("You: see you at 6").assertIsDisplayed()
    }

    @Test fun anUnknownNameShowsSomeone() {
        show(base().copy(previewSender = ""))
        rule.onNodeWithText("Someone: see you at 6").assertIsDisplayed()
    }

    @Test fun aOneToOnePreviewHasNoName() {
        show(base())
        rule.onNodeWithText("see you at 6").assertIsDisplayed()
    }
}
