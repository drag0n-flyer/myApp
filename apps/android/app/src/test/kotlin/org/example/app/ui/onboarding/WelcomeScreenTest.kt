package org.example.app.ui.onboarding

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
class WelcomeScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun showsThreePointsAndBothButtons() {
        var started = 0; var have = 0
        rule.setContent { AppTheme { WelcomeScreen(true, { started++ }, { have++ }) } }
        rule.onNodeWithText("Uses very little data").assertExists()
        rule.onNodeWithText("Private by default").assertExists()
        rule.onNodeWithText("Your own server").assertExists()
        rule.onNodeWithText("Welcome to [App]").assertExists()
        rule.onNodeWithText("Get started").performClick()
        rule.onNodeWithText("I already have an account").performClick()
        assertEquals(1, started); assertEquals(1, have)
    }

    @Test
    fun deviceThatCannotCreateAccountsOnlyLinks() {
        rule.setContent { AppTheme { WelcomeScreen(false, {}, {}) } }
        rule.onNodeWithText("Get started").assertDoesNotExist()
        rule.onNodeWithText("Link to your other phone").assertExists()
    }
}
