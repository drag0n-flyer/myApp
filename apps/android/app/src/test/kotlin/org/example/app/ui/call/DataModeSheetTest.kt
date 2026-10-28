package org.example.app.ui.call

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.DataModePolicy
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.settings.CallSettings
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class DataModeSheetTest {
    @get:Rule val rule = createComposeRule()

    private val picked = mutableListOf<ModePreference>()

    private fun launch(max: DataMode, network: NetworkType, inCall: ModePreference? = null) {
        val server = ServerPolicy(ModePreference.Auto, max, IPHiding.NonContacts, true, "home.example.org")
        val options = DataModePolicy.sheetOptions(server, CallSettings(), network, inCall)
        rule.setContent { AppTheme { DataModeSheetContent(options, onSelect = { picked += it }) } }
    }

    @Test
    fun listsAllModesWithEstimates() {
        launch(DataMode.High, NetworkType.Wifi)
        for (t in listOf("Data for this call", "Auto", "Ultra-low", "Data saver", "Balanced", "High quality")) {
            rule.onNode(hasText(t)).assertIsDisplayed()
        }
        for (t in listOf("~2 MB per 10 min", "~3 MB per 10 min", "~5 MB per 10 min", "~6 MB per 10 min")) {
            rule.onNodeWithText(t).assertIsDisplayed()
        }
        rule.onNodeWithText("This call only · audio, both directions, estimated").assertIsDisplayed()
    }

    @Test
    fun modeAboveServerMaximumIsDisabled() {
        launch(DataMode.DataSaver, NetworkType.Wifi)
        rule.onNodeWithText("Balanced", useUnmergedTree = false).assertIsNotEnabled()
        rule.onNodeWithText("High quality").assertIsNotEnabled()
        rule.onNodeWithText("Data saver").assertIsEnabled()
        rule.onAllNodesWithText("Limited by your server", useUnmergedTree = true).assertCountEquals(2)  // Balanced and High
        rule.onNodeWithText("Balanced").performClick()
        assertEquals(emptyList(), picked)
    }

    @Test
    fun modeAboveMobileLimitStaysEnabledWithNote() {
        launch(DataMode.High, NetworkType.Mobile)
        rule.onNodeWithText("Balanced").assertIsEnabled()
        rule.onAllNodesWithText("Above your mobile data limit", useUnmergedTree = true).assertCountEquals(2)  // Balanced and High
        rule.onNodeWithText("Balanced").performClick()
        assertEquals(listOf<ModePreference>(ModePreference.Fixed(DataMode.Balanced)), picked)
    }

    @Test
    fun noMobileNoteOnWifi() {
        launch(DataMode.High, NetworkType.Wifi)
        rule.onAllNodesWithText("Above your mobile data limit", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun selectedRowIsMarked() {
        launch(DataMode.High, NetworkType.Wifi, inCall = ModePreference.Fixed(DataMode.Balanced))
        rule.onNodeWithText("Balanced").assertIsSelected()
        rule.onNodeWithText("Auto").performClick()
        assertEquals(listOf<ModePreference>(ModePreference.Auto), picked)
    }
}
