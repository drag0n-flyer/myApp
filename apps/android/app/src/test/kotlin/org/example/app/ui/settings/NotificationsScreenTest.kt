package org.example.app.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
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
class NotificationsScreenTest {
    @get:Rule val rule = createComposeRule()
    private var delivery = NotificationDelivery.Google
    private var opened = 0

    private fun launch(canFullScreen: Boolean) = rule.setContent {
        AppTheme {
            NotificationsScreen(
                delivery = delivery, onDeliveryChange = { delivery = it },
                canUseFullScreenIntent = canFullScreen, onOpenFullScreenSettings = { opened++ }, onBack = {},
            )
        }
    }

    @Test
    fun showsDeliveryChoices() {
        launch(true)
        rule.onNodeWithText("Notification delivery").assertIsDisplayed()
        rule.onNodeWithText("Google").assertIsSelected()
        rule.onNodeWithText("UnifiedPush").assertIsDisplayed()
        rule.onNodeWithText("UnifiedPush").performClick()
        assertEquals(NotificationDelivery.UnifiedPush, delivery)
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
