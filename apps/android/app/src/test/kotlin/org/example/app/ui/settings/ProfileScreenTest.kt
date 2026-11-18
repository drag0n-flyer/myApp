package org.example.app.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import org.example.app.core.account.AccountError
import org.example.app.core.chats.Contact
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class ProfileScreenTest {
    @get:Rule val rule = createComposeRule()
    private var signOuts = 0
    private var deletes = 0
    private var failure: Exception? = null

    private var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    private val vm by lazy {
        ProfileViewModel(
            signOutAction = { signOuts++; gate?.await(); failure?.let { throw it } },
            deleteAction = { deletes++; failure?.let { throw it } },
        )
    }

    private fun launch() = rule.setContent {
        AppTheme { ProfileScreen(Contact("me", "Ana", "ana"), vm, onBack = {}) }
    }

    @Test
    fun whileRunningShowsProgressAndIgnoresMoreTaps() {
        gate = kotlinx.coroutines.CompletableDeferred()
        launch()
        rule.onNodeWithText("Sign out").performClick()
        rule.onAllNodesWithText("Sign out").onLast().performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Signing out").assertIsDisplayed()
        assertEquals(1, signOuts)
        vm.signOut() // a second request while one runs does nothing
        vm.deleteAccount()
        rule.waitForIdle()
        assertEquals(1, signOuts)
        assertEquals(0, deletes)
        gate!!.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Signing out").assertDoesNotExist()
    }

    @Test
    fun theRunningStateSurvivesTheScreenBeingRecreated() {
        gate = kotlinx.coroutines.CompletableDeferred()
        vm.signOut()
        launch() // a new composition over the same view model, as after a rotation
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Signing out").assertIsDisplayed()
        gate!!.complete(Unit)
    }

    @Test
    fun signOutAsksFirstWithTheExactText() {
        launch()
        rule.onNodeWithText("Ana").assertIsDisplayed()
        rule.onNodeWithText("Sign out").performClick()
        rule.onNodeWithText("Sign out of this device? Your messages on this device will be removed.").assertIsDisplayed()
        assertEquals(0, signOuts)
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(0, signOuts)
    }

    @Test
    fun confirmingSignOutRunsIt() {
        launch()
        rule.onNodeWithText("Sign out").performClick()
        rule.onAllNodesWithText("Sign out").onLast().performClick()
        rule.waitForIdle()
        assertEquals(1, signOuts)
    }

    @Test
    fun withNoConnectionSignOutSaysSoAndCanBeRetried() {
        failure = AccountError.Network
        launch()
        rule.onNodeWithText("Sign out").performClick()
        rule.onAllNodesWithText("Sign out").onLast().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("No connection").assertIsDisplayed()
        // Still there to try again.
        failure = null
        rule.onNodeWithText("Sign out").performClick()
        rule.onAllNodesWithText("Sign out").onLast().performClick()
        rule.waitForIdle()
        assertEquals(2, signOuts)
        rule.onNodeWithText("No connection").assertDoesNotExist()
    }

    @Test
    fun deleteAccountAsksFirstAndSaysNoConnection() {
        failure = AccountError.Network
        launch()
        rule.onNodeWithText("Delete account").performClick()
        rule.onNodeWithText("Delete your account? Your messages on this device are erased and can't be recovered.").assertIsDisplayed()
        assertEquals(0, deletes)
        rule.onAllNodesWithText("Delete account").onLast().performClick()
        rule.waitForIdle()
        assertEquals(1, deletes)
        rule.onNodeWithText("No connection").assertIsDisplayed()
    }
}
