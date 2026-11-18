package org.example.app.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import org.example.app.push.PushTransport
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class NotificationsScreenTest {
    @get:Rule val rule = createComposeRule()
    private var opened = 0

    private fun launch(canFullScreen: Boolean, transport: PushTransport = PushTransport.Fcm) = rule.setContent {
        AppTheme {
            NotificationsScreen(transport = transport, canUseFullScreenIntent = canFullScreen, onOpenFullScreenSettings = { opened++ }, onBack = {})
        }
    }

    @Test
    fun namesTheTransportInUseAndOffersNoChoice() {
        launch(true, PushTransport.UnifiedPush)
        rule.onNodeWithText("Notification delivery").assertIsDisplayed()
        rule.onNodeWithText("Delivered through").assertIsDisplayed()
        rule.onNodeWithText("UnifiedPush").assertIsDisplayed()
        rule.onNodeWithText("Google").assertDoesNotExist()
        rule.onNodeWithText("UnifiedPush").performClick() // a read-only row: nothing to select, nothing happens
    }

    @Test
    fun saysSoWhenNoPushServiceIsSetUp() {
        launch(true, PushTransport.None)
        rule.onNodeWithText("No push service set up").assertIsDisplayed()
    }

    @Test
    fun fullScreenRowOnlyWhenNotAllowed() {
        launch(true)
        rule.onNodeWithText("Show incoming calls over the lock screen").assertDoesNotExist()
    }

    @Test
    fun fullScreenRowOpensSystemPage() {
        launch(false)
        rule.onNodeWithText("Show incoming calls over the lock screen").performClick()
        assertEquals(1, opened)
    }
}
