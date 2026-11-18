package org.example.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.example.app.core.chats.LinkPreviewCard
import org.example.app.core.chats.LinkPreviewMaker
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class ComposerPreviewRestoreTest {
    @get:Rule val rule = createComposeRule()

    private val card = LinkPreviewCard("https://example.org/a", "A title", null, null)

    @Test fun aRemovedPreviewStaysRemovedAfterAConfigurationChange() {
        val urls = mutableListOf<String>()
        val support = LinkPreviewSupport({ url -> urls += url; card }, MutableStateFlow(true))
        val tester = StateRestorationTester(rule)
        tester.setContent {
            CompositionLocalProvider(LocalLinkPreviews provides support) {
                val preview = rememberComposerPreview("see https://example.org/a", editing = false)
                Column {
                    preview.card?.let { Text(it.title) }
                    TextButton(onClick = preview::remove) { Text("dismiss") }
                }
            }
        }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        rule.onNodeWithText("A title").assertIsDisplayed()
        rule.onNodeWithText("dismiss").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("A title").assertDoesNotExist()

        tester.emulateSavedInstanceStateRestore()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        rule.onNodeWithText("A title").assertDoesNotExist()
        assertEquals(1, urls.size, "the link is not looked up again for the same draft")
    }
}
