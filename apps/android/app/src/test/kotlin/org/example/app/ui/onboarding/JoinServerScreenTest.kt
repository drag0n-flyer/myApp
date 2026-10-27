package org.example.app.ui.onboarding

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class JoinServerScreenTest {
    @get:Rule val rule = createComposeRule()

    private var default = 0
    private val links = mutableListOf<String>()
    private val addresses = mutableListOf<String>()
    private var scanned: Boolean? = null

    /** A scanner that reports one fixed code when tapped. */
    private val fakeScanner: @Composable (onResult: (String) -> Unit) -> Unit = { onResult ->
        Text("fake scanner", Modifier.clickable { onResult("https://chat.example.org/invite/ABCDE-FGH23") })
    }

    private fun launch(error: Boolean = false) = rule.setContent {
        AppTheme {
            JoinServerScreen(
                error = null,
                onDefaultServer = { default++ },
                onInviteText = { links += it; it.startsWith("https://") },
                onAddress = { addresses += it },
                scanner = fakeScanner,
            )
        }
    }

    @Test
    fun appServerCardIsFirstAndRecommended() {
        launch()
        rule.onNodeWithText("Recommended").assertExists()
        val card = rule.onNodeWithText("[App] server").fetchSemanticsNode().boundsInRoot.top
        val other = rule.onNodeWithText("Or join another server").fetchSemanticsNode().boundsInRoot.top
        assertTrue(card < other)
        rule.onNodeWithText("Invite code needed during the beta").assertExists()
        rule.onNodeWithText("[App] server").performClick()
        assertEquals(1, default)
    }

    @Test
    fun scanningAnInviteQrCodeHandsTheTextOn() {
        launch()
        rule.onNodeWithText("Scan invite QR code").performClick()
        rule.onNodeWithText("fake scanner").performClick()
        assertEquals(listOf("https://chat.example.org/invite/ABCDE-FGH23"), links)
    }

    @Test
    fun pastedTextThatIsNotAnInviteShowsAnError() {
        launch()
        rule.onNodeWithText("Paste invite link").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("nonsense")
        rule.onNodeWithText("Join").performClick()
        rule.onNodeWithText("That isn't an invite link.").assertExists()
    }

    @Test
    fun serverAddressIsPassedOn() {
        launch()
        rule.onNodeWithText("Enter server address").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("chat.example.org")
        rule.onNodeWithText("Continue").performClick()
        assertEquals(listOf("chat.example.org"), addresses)
    }
}
