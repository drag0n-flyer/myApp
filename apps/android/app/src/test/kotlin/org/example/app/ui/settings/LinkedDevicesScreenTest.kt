package org.example.app.ui.settings

import java.time.Instant
import org.example.app.core.account.SampleAccountService
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
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
class LinkedDevicesScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun listsNameTypeAndLastActive() {
        val account = SampleAccountService()
        rule.setContent { AppTheme { LinkedDevicesScreen(account, onBack = {}) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("iPhone").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("This phone").assertIsDisplayed()
        rule.onNodeWithText("Android").assertIsDisplayed()
        rule.onNodeWithText("Active now").assertIsDisplayed()
        rule.onNodeWithText("iPhone", substring = false).assertIsDisplayed()
        assertTrue(rule.allLabels().any { it.startsWith("Last active") })
    }

    @Test
    fun removeAsksFirst() {
        val account = SampleAccountService()
        rule.setContent { AppTheme { LinkedDevicesScreen(account, onBack = {}) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("iPhone").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Remove iPhone").performClick()
        rule.onNodeWithText("Remove this device?").assertIsDisplayed()
    }

    @Test
    fun linkADeviceItemCallsBack() {
        val account = SampleAccountService()
        var linked = 0
        rule.setContent { AppTheme { LinkedDevicesScreen(account, onBack = {}, onLinkDevice = { linked++ }) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("iPhone").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Link a device").performClick()
        assertEquals(1, linked)
    }

    private class Failing(
        private val inner: SampleAccountService = SampleAccountService(),
        var failLoad: Boolean = false,
        var failRemove: Boolean = false,
    ) : org.example.app.core.account.AccountService by inner {
        override suspend fun devices() = if (failLoad) throw org.example.app.core.account.AccountError.Network else inner.devices()
        override suspend fun removeDevice(id: String) { if (failRemove) throw org.example.app.core.account.AccountError.Network else inner.removeDevice(id) }
    }

    @Test
    fun failedLoadIsNotShownAsAnEmptyListAndCanBeRetried() {
        val account = Failing(failLoad = true)
        rule.setContent { AppTheme { LinkedDevicesScreen(account, onBack = {}) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Couldn't load your devices").fetchSemanticsNodes().isNotEmpty() }
        account.failLoad = false
        rule.onNodeWithText("Try again").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("iPhone").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Couldn't load your devices").assertDoesNotExist()
    }

    @Test
    fun failedRemovalShowsAnError() {
        val account = Failing(failRemove = true)
        rule.setContent { AppTheme { LinkedDevicesScreen(account, onBack = {}) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("iPhone").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Remove iPhone").performClick()
        rule.onNodeWithText("Remove").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Couldn't remove the device. Try again.").fetchSemanticsNodes().isNotEmpty() }
    }
}
