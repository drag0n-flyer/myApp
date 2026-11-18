package org.example.app

import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.ui.chat.ChatScreen
import org.example.app.ui.chat.ChatViewModel
import org.example.app.ui.theme.AppTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Media in the chat on sample data, in the real app on a real (emulated) device: the photo bubble and the attach sheet. */
@RunWith(AndroidJUnit4::class)
class ChatMediaTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    private fun openRiya() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SAMPLE_DATA, true)
        scenario = ActivityScenario.launch(intent)
        rule.onNodeWithText("Riya").performClick()
    }

    @After
    fun close() = scenario.close()

    @Test
    fun aSamplePhotoBubbleIsDisplayed() {
        openRiya()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Photo · ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Photo · ", substring = true)).assertIsDisplayed()
    }

    @Test
    fun theAttachSheetOffersPhotoAndFile() {
        openRiya()
        rule.onNodeWithContentDescription("Attach").performClick()
        rule.onNodeWithText("Photo").assertIsDisplayed()
        rule.onNodeWithText("File").assertIsDisplayed()
    }
}

/**
 * The attach sheet's Photo launches the system photo picker. The launch is caught by a stub registry instead of opening
 * the picker, so the test sees the exact intent the phone would have started.
 */
@RunWith(AndroidJUnit4::class)
class AttachPickerTest {
    @get:Rule val rule = createComposeRule()

    private class StubRegistry : ActivityResultRegistry() {
        val intents = mutableListOf<Intent>()

        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            intents += contract.createIntent(ApplicationProvider.getApplicationContext(), input)
            // The user backs out of the picker.
            dispatchResult(requestCode, android.app.Activity.RESULT_CANCELED, Intent())
        }
    }

    @Test
    fun photoLaunchesThePhotoPickerForImages() {
        val registry = StubRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val vm = ChatViewModel(SampleChatStore(), "chat-riya", NetworkType.Wifi) { AutoDownloadSettings() }
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                AppTheme { ChatScreen(vm, myId = "me", onBack = {}, onOpenInfo = {}, onCall = null) }
            }
        }
        rule.onNodeWithContentDescription("Attach").performClick()
        rule.onNodeWithText("Photo").performClick()
        val intent = registry.intents.single()
        // The system photo picker where there is one, the document picker narrowed to images where there is not.
        assertTrue(
            intent.action == MediaStore.ACTION_PICK_IMAGES || intent.action == Intent.ACTION_OPEN_DOCUMENT || intent.action == Intent.ACTION_PICK,
            "action=${intent.action}",
        )
        assertEquals("image/*", intent.type)
    }
}
