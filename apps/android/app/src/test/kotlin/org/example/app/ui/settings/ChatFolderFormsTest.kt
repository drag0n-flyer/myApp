package org.example.app.ui.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.StateRestorationTester
import kotlin.test.assertEquals
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The dialog bodies, tested as plain composables (a text field inside a dialog window hangs Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class ChatFolderFormsTest {
    @get:Rule val rule = createComposeRule()

    private val chats = listOf("c1" to "Riya", "c2" to "Sam")

    @Test
    fun renameStartsWithTheCurrentNameAndSavesTheTrimmedText() {
        val saved = mutableListOf<String>()
        rule.setContent { AppTheme { RenameFolderForm("Work", onSave = { saved += it }, onCancel = {}) } }
        rule.onNodeWithText("Work").assertExists()
        rule.onNodeWithText("Work").performTextClearance()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Folder name").performTextInput("  Office ")
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(listOf("Office"), saved)
    }

    @Test
    fun renameCancelDoesNotSave() {
        var cancelled = 0
        val saved = mutableListOf<String>()
        rule.setContent { AppTheme { RenameFolderForm("Work", onSave = { saved += it }, onCancel = { cancelled++ }) } }
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
        assertEquals(emptyList(), saved)
    }

    @Test
    fun renameKeepsTheTypedTextAcrossRotation() {
        val tester = StateRestorationTester(rule)
        tester.setContent { AppTheme { RenameFolderForm("Work", onSave = {}, onCancel = {}) } }
        rule.onNodeWithText("Work").performTextReplacement("Office")
        rule.onNodeWithText("Office").assertExists()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Office").assertExists()
        rule.onNodeWithText("Work").assertDoesNotExist()
    }

    @Test
    fun newFolderNeedsANameAndCreatesWithTheChosenChats() {
        val created = mutableListOf<Pair<String, Set<String>>>()
        rule.setContent { AppTheme { NewFolderForm(chats, onCreate = { n, c -> created += n to c }, onCancel = {}) } }
        rule.onNodeWithText("Create").assertIsNotEnabled()
        rule.onNodeWithText("Folder name").performTextInput(" Friends ")
        rule.onNodeWithText("Sam").performClick()
        rule.onNodeWithText("Create").assertIsEnabled().performClick()
        assertEquals(listOf("Friends" to setOf("c2")), created)
    }

    @Test
    fun newFolderKeepsNameAndChosenChatsAcrossRotation() {
        val created = mutableListOf<Pair<String, Set<String>>>()
        val tester = StateRestorationTester(rule)
        tester.setContent { AppTheme { NewFolderForm(chats, onCreate = { n, c -> created += n to c }, onCancel = {}) } }
        rule.onNodeWithText("Folder name").performTextInput("Friends")
        rule.onNodeWithText("Riya").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Create").assertIsEnabled().performClick()
        assertEquals(listOf("Friends" to setOf("c1")), created)
    }
}
