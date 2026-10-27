package org.example.app.ui.calls

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.core.chats.CallSummary
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class CallHistoryTest {
    @get:Rule val rule = createComposeRule()

    private val store = SampleChatStore(SampleData.make(Instant.now()))
    private val calls = mutableListOf<Pair<String, Boolean>>()

    private fun launch() {
        val vm = CallHistoryViewModel(store)
        rule.setContent { AppTheme { CallHistoryScreen(vm, onCall = { id, video -> calls += id to video }) } }
    }

    @Test
    fun listsCallsNewestFirst() {
        launch()
        rule.onNodeWithText("Arjun").assertIsDisplayed()
        rule.onNodeWithText("Dev").assertIsDisplayed()
        val vm = CallHistoryViewModel(store)
        val dates = vm.records.value.map { it.at }
        assertEquals(dates.sortedDescending(), dates)
        assertTrue(vm.records.value.isNotEmpty())
    }

    @Test
    fun missedChipFilters() {
        launch()
        rule.onNodeWithText("All").assertIsSelected()
        rule.onNodeWithText("Missed", useUnmergedTree = false).performClick()
        rule.onNodeWithText("Missed").assertIsSelected()
        rule.onNodeWithText("Arjun").assertIsDisplayed()
        rule.onNodeWithText("Dev").assertDoesNotExist()
        rule.onNodeWithText("All").performClick()
        rule.onNodeWithText("Dev").assertIsDisplayed()
    }

    @Test
    fun missedRowsAreFlaggedMissed() {
        val vm = CallHistoryViewModel(store)
        val arjun = vm.records.value.first { it.name == "Arjun" }
        assertTrue(arjun.isMissed)
        assertEquals(CallSummary.MissedAudio, arjun.summary)
        assertTrue(!vm.records.value.first { it.name == "Dev" }.isMissed)
    }

    @Test
    fun callBackButtonStartsACall() {
        launch()
        rule.onNodeWithContentDescription("Call Dev").performClick()
        assertEquals(listOf("dev" to true), calls)
    }

    @Test
    fun everyClickableHasALabel() {
        launch()
        for (n in rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val text = n.config.getOrNull(SemanticsProperties.Text).orEmpty().isNotEmpty()
            val cd = n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().isNotEmpty()
            assertTrue(text || cd, "unlabelled clickable")
        }
    }
}
