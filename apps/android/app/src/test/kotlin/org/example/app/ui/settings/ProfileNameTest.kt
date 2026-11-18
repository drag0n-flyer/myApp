package org.example.app.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.example.app.R
import org.example.app.core.account.AccountError
import org.example.app.core.chats.Contact
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Editing the display name: the rule, one save at a time, the failure text, and the form. The dialog window itself is
 * not opened here (a text field inside a dialog window hangs Robolectric); its body [EditNameForm] is tested plain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class ProfileNameTest {
    @get:Rule val rule = createComposeRule()

    private val saved = mutableListOf<String>()
    private var failure: Exception? = null
    private var gate: CompletableDeferred<Unit>? = null
    private var signOuts = 0
    private val me = Contact("me", "Ana", "ana")

    private val actions = AccountActions(
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        signOut = { signOuts++ },
        deleteAccount = {},
        saveName = { name -> saved += name; gate?.await(); failure?.let { throw it } },
    )
    private val vm by lazy { ProfileViewModel(actions) }

    private fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun launch() = rule.setContent { AppTheme { ProfileScreen(me, vm, onBack = {}) } }

    @Test fun savesTheCleanedName() {
        vm.saveName("  Asha  ")
        idle()
        assertEquals(listOf("Asha"), saved)
        assertEquals(null, vm.state.value.error)
    }

    @Test fun refusedNamesNeverReachTheSave() {
        for (bad in listOf("", "   ", "a".repeat(41), "Ana\nMaria", "Asha‮", "A⁦B", "A‏B", "A؜B")) vm.saveName(bad)
        idle()
        assertEquals(emptyList(), saved)
        // Nothing is stuck: a good name goes straight through afterwards.
        vm.saveName("Asha")
        idle()
        assertEquals(listOf("Asha"), saved)
    }

    @Test fun theEditorClosesWhenSignOutStartsAndCannotOpenWhileItRuns() {
        val held = CompletableDeferred<Unit>()
        val busy = AccountActions(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            signOut = { held.await() }, deleteAccount = {}, saveName = { saved += it },
        )
        val model = ProfileViewModel(busy)
        model.openNameEditor()
        idle()
        assertEquals(true, model.state.value.editingName)
        model.signOut()
        idle()
        assertEquals(false, model.state.value.editingName, "closed when sign-out started")
        model.openNameEditor()
        idle()
        assertEquals(false, model.state.value.editingName, "cannot open while it runs")
        held.complete(Unit)
        idle()
        assertEquals(false, model.state.value.editingName, "does not come back by itself")
        model.openNameEditor()
        idle()
        assertEquals(true, model.state.value.editingName)
        model.closeNameEditor()
        idle()
        assertEquals(false, model.state.value.editingName)
    }

    @Test fun savingClosesTheEditor() {
        vm.openNameEditor()
        idle()
        vm.saveName("Asha")
        idle()
        assertEquals(false, vm.state.value.editingName)
    }

    @Test fun anUnacceptableNameKeepsTheEditorOpen() {
        vm.openNameEditor()
        idle()
        vm.saveName("   ")
        idle()
        assertEquals(true, vm.state.value.editingName)
    }

    @Test fun theFormShowsTheCountOfTheTrimmedText() {
        rule.setContent { AppTheme { EditNameForm("  Asha ", onSave = {}, onCancel = {}) } }
        rule.onNodeWithText("4/40").assertIsDisplayed()
    }

    @Test fun anEmptyStartLeavesSaveOffUntilSomethingValidIsTyped() {
        rule.setContent { AppTheme { EditNameForm("", onSave = {}, onCancel = {}) } }
        rule.onNodeWithText("0/40").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Name").performTextInput("\u200B")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Name").performTextReplacement("Asha")
        rule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test fun aFailureSaysTheExactTextWhateverWentWrong() {
        for (e in listOf(AccountError.Network, AccountError.Server, IllegalStateException("x"))) {
            failure = e
            vm.saveName("Asha")
            idle()
            assertEquals(R.string.profile_name_error, vm.state.value.error, e.toString())
        }
    }

    @Test fun oneSaveAtATime() {
        gate = CompletableDeferred()
        vm.saveName("Asha")
        idle()
        assertEquals(ProfileAction.SavingName, vm.state.value.busy)
        vm.saveName("Bela")
        vm.signOut()
        assertEquals(listOf("Asha"), saved)
        assertEquals(0, signOuts)
        gate!!.complete(Unit)
        idle()
        vm.saveName("Bela")
        assertEquals(listOf("Asha", "Bela"), saved)
    }

    @Test fun theFormStartsWithTheNameAndSaveWaitsForAnAcceptableOne() {
        val got = mutableListOf<String>()
        rule.setContent { AppTheme { EditNameForm("Ana", onSave = { got += it }, onCancel = {}) } }
        rule.onNodeWithText("Edit name").assertIsDisplayed()
        rule.onNodeWithText("Ana").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Ana").performTextClearance()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Name").performTextInput("   ")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Name").performTextReplacement("Asha‮")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Name").performTextReplacement(" Asha ")
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(listOf("Asha"), got)
    }

    @Test fun cancelInTheFormSavesNothing() {
        val got = mutableListOf<String>()
        var cancelled = 0
        rule.setContent { AppTheme { EditNameForm("Ana", onSave = { got += it }, onCancel = { cancelled++ }) } }
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
        assertEquals(emptyList(), got)
    }

    @Test fun aFailedSaveShowsTheMessageOnTheScreen() {
        failure = AccountError.Network
        launch()
        vm.saveName("Asha")
        idle()
        rule.waitForIdle()
        rule.onNodeWithText("Couldn't save your name. Try again.").assertIsDisplayed()
    }
}
