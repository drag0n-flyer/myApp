package org.example.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.settings.IPHiding
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onNodeWithContentDescription
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
class PrivacyScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val settings = SettingsStore()
    private var saveEmail: suspend (Boolean) -> Unit = {}
    private var serverEmail: Boolean? = null
    private var loadGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private val saved = mutableListOf<Boolean>()

    private fun launch(store: SampleChatStore = SampleChatStore()) = rule.setContent {
        AppTheme { PrivacyScreen(settings, store, saveFindByEmail = { saved += it; saveEmail(it) }, loadFindByEmail = { loadGate?.await(); serverEmail }, onBack = {}) }
    }

    @Test
    fun hideIpOffersThreeChoicesWithRecommendedSelected() {
        launch()
        rule.onNodeWithText("Hide my IP address in calls").assertIsDisplayed()
        rule.onNodeWithText("Never").assertIsDisplayed()
        rule.onNodeWithText("With people not in my contacts").assertIsDisplayed()
        rule.onNodeWithText("Always").assertIsDisplayed()
        rule.onNodeWithText("Recommended").assertIsDisplayed()
        rule.onNodeWithText("With people not in my contacts").assertIsSelected()
    }

    @Test
    fun choosingAnOptionUpdatesTheSetting() {
        launch()
        rule.onNodeWithText("Always").performClick()
        rule.waitForIdle()
        assertEquals(IPHiding.Always, settings.settings.value.ipHiding)
        rule.onNodeWithText("Always").assertIsSelected()
    }

    @Test
    fun findByEmailIsOffByDefaultAndThereIsNoUsernameSwitch() {
        launch()
        rule.onNodeWithText("By email").assertIsDisplayed().assertIsOff()
        rule.onNodeWithText("By username").assertDoesNotExist()
        rule.onNodeWithText("Disappearing messages").assertDoesNotExist()
    }

    @Test
    fun findByEmailIsSentToTheServerAndKept() {
        launch()
        rule.onNodeWithText("By email").performClick()
        rule.waitForIdle()
        assertEquals(listOf(true), saved)
        rule.onNodeWithText("By email").assertIsOn()
        assertTrue(settings.settings.value.findByEmail)
    }

    @Test
    fun findByEmailRevertsWithAnErrorWhenTheServerFails() {
        saveEmail = { throw org.example.app.core.account.AccountError.Server }
        launch()
        rule.onNodeWithText("By email").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("By email").assertIsOff()
        assertEquals(false, settings.settings.value.findByEmail)
        rule.onNodeWithText("Something went wrong on the server. Try again later.").assertIsDisplayed()
    }

    @Test
    fun findByEmailRevertsWithNoConnectionMessage() {
        saveEmail = { throw org.example.app.core.account.AccountError.Network }
        launch()
        rule.onNodeWithText("By email").performClick()
        rule.waitForIdle()
        assertEquals(false, settings.settings.value.findByEmail)
        rule.onNodeWithText("Can't reach the server. Check your connection and try again.").assertIsDisplayed()
    }

    @Test
    fun theServersValueIsShownWhenTheScreenOpens() {
        serverEmail = true
        launch()
        rule.waitForIdle()
        rule.onNodeWithText("By email").assertIsOn()
        assertTrue(settings.settings.value.findByEmail)
    }

    @Test
    fun theServersValueDoesNotOverwriteAChangeMadeWhileItLoaded() {
        serverEmail = false
        loadGate = kotlinx.coroutines.CompletableDeferred()
        launch()
        rule.onNodeWithText("By email").performClick()
        rule.waitForIdle()
        loadGate!!.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithText("By email").assertIsOn()
    }

    @Test
    fun aToggleWhileASaveIsInFlightIsIgnored() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        saveEmail = { gate.await() }
        launch()
        rule.onNodeWithText("By email").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("By email").performClick()
        rule.waitForIdle()
        assertEquals(listOf(true), saved)
        gate.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithText("By email").performClick()
        rule.waitForIdle()
        assertEquals(listOf(true, false), saved)
    }

    @Test
    fun blockedListShowsNamesAndUnblocks() {
        val store = SampleChatStore()
        store.block("sam")
        launch(store)
        rule.onNodeWithText("Blocked").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Unblock sam").assertIsDisplayed()
        rule.onNodeWithContentDescription("Unblock sam").performClick()
        rule.waitForIdle()
        assertTrue("sam" !in store.blockedIds.value)
        rule.onNodeWithText("No one blocked").assertIsDisplayed()
    }

    @Test
    fun aBlockedPersonWithoutANameShowsTheUsernameThenTheId() {
        val store = SampleChatStore()
        assertEquals("Riya", blockedName(store, "riya"))
        assertEquals("unknown-id", blockedName(store, "unknown-id"))
    }

    @Test
    fun usesPlainWords() {
        launch()
        val text = rule.allLabels().joinToString(" ")
        assertTrue(Regex("relay|MLS|E2EE|codec|kbps", RegexOption.IGNORE_CASE).containsMatchIn(text).not())
    }

    @Test
    fun systemBackInBlockedListReturnsToPrivacyPage() {
        var leftPrivacy = false
        rule.setContent { AppTheme { PrivacyScreen(settings, SampleChatStore(), saveFindByEmail = {}, loadFindByEmail = { null }, onBack = { leftPrivacy = true }) } }
        rule.onNodeWithText("Blocked").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Hide my IP address in calls").assertDoesNotExist()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Hide my IP address in calls").assertIsDisplayed()
        assertEquals(false, leftPrivacy)
    }
}
