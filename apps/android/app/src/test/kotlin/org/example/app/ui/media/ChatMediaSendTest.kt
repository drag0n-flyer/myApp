package org.example.app.ui.media

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.core.settings.MediaKind
import org.example.app.media.MediaCache
import org.example.app.ui.chat.ChatScreen
import org.example.app.ui.chat.ChatViewModel
import org.example.app.ui.chat.Composer
import org.example.app.ui.msg
import org.example.app.ui.storeWith
import org.example.app.ui.theme.AppTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A registry that records what was launched and answers at once with what [answer] says (null: the user backed out). */
private class AnsweringRegistry(private val answer: (ActivityResultContract<*, *>, Any?) -> Any?) : ActivityResultRegistry() {
    val launched = mutableListOf<Pair<ActivityResultContract<*, *>, Any?>>()

    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        launched += contract to input
        val result = answer(contract, input)
        if (result == null) {
            dispatchResult(requestCode, android.app.Activity.RESULT_CANCELED, android.content.Intent())
        } else {
            @Suppress("UNCHECKED_CAST")
            dispatchResult(requestCode, result as O)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class ChatMediaSendTest {
    @get:Rule val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private class RecordingStore(inner: SampleChatStore) : ChatStore by inner {
        val sent = mutableListOf<Pair<String, MediaKind>>()
        val problem = kotlinx.coroutines.flow.MutableStateFlow<org.example.app.core.chats.SendProblem.Reason?>(null)
        var taken = 0
        override fun sendProblem(chatId: String): kotlinx.coroutines.flow.Flow<org.example.app.core.chats.SendProblem.Reason?> = problem
        override fun takeSendProblem(chatId: String) { taken++; problem.value = null }
        override fun sendAttachment(path: String, kind: MediaKind, chatId: String) { sent += path to kind }
    }

    private val store = RecordingStore(storeWith(listOf(msg("1", "riya", MessageContent.Text("hello")))))

    @Before fun noProviderFolders() {
        // FileProvider remembers the folders it serves for the whole process; every Robolectric test has a cache folder of its own.
        androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").also { it.isAccessible = true }
            .let { (it.get(null) as MutableMap<*, *>).clear() }
    }

    private fun launch(registry: ActivityResultRegistry) {
        val vm = ChatViewModel(store, "chat-riya", NetworkType.Wifi) { AutoDownloadSettings() }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                AppTheme { ChatScreen(vm, myId = "me", onBack = {}, onOpenInfo = {}, onCall = null) }
            }
        }
    }

    private fun attach(row: String) {
        rule.onNodeWithContentDescription("Attach").performClick()
        rule.onNodeWithText(row).performClick()
    }

    private fun cameraFileOf(uri: Uri): File = File(SharedFiles.cameraDir(app), uri.lastPathSegment!!)

    @Test fun photoOpensThePhotoPickerForImagesAndSendsTheChosenPhoto() {
        val picked = Uri.parse("content://media/picker/0/1")
        val registry = AnsweringRegistry { c, _ -> if (c is ActivityResultContracts.PickVisualMedia) picked else null }
        launch(registry)
        attach("Photo")
        val (contract, input) = registry.launched.single()
        assertTrue(contract is ActivityResultContracts.PickVisualMedia)
        assertEquals(ActivityResultContracts.PickVisualMedia.ImageOnly, (input as androidx.activity.result.PickVisualMediaRequest).mediaType)
        assertEquals(listOf(picked.toString() to MediaKind.Photo), store.sent)
    }

    @Test fun aPhotoTheStoreReportsAsTooBigOrUnreadableShowsTheSameMessagesAsAFile() {
        // Raised while the chat was closed: shown when it is opened, once.
        store.problem.value = org.example.app.core.chats.SendProblem.Reason.TooBig
        launch(AnsweringRegistry { _, _ -> null })
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("That file is too big to send.")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, store.taken)
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("That file is too big to send.")).fetchSemanticsNodes().isEmpty() }
        store.problem.value = org.example.app.core.chats.SendProblem.Reason.Unreadable
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Couldn't read that file.")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, store.taken)
    }

    @Test fun backingOutOfThePhotoPickerSendsNothing() {
        launch(AnsweringRegistry { _, _ -> null })
        attach("Photo")
        assertEquals(emptyList(), store.sent)
    }

    @Test fun aPhotoThatIsNotAContentUriIsRefusedWithAMessage() {
        launch(AnsweringRegistry { c, _ -> if (c is ActivityResultContracts.PickVisualMedia) Uri.parse("file:///data/data/${app.packageName}/databases/app.db") else null })
        attach("Photo")
        assertEquals(emptyList(), store.sent)
        rule.onNodeWithText("Couldn't read that file.").assertIsDisplayed()
    }

    @Test fun aPickedFileIsCopiedIntoAppStorageUnderACleanNameBeforeItIsSent() {
        val uri = Uri.parse("content://docs/..%2F..%2Fevil.txt")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream("contents".toByteArray()))
        launch(AnsweringRegistry { c, _ -> if (c is ActivityResultContracts.OpenDocument) uri else null })
        attach("File")
        rule.waitUntil(5_000) { store.sent.isNotEmpty() }
        val (path, kind) = store.sent.single()
        assertEquals(MediaKind.File, kind)
        val file = File(path)
        assertEquals("evil.txt", file.name)
        assertEquals("contents", file.readText())
        assertTrue(file.canonicalPath.startsWith(File(MediaCache.outgoingDir(app), "files").canonicalPath + File.separator))
    }

    @Test fun aFileThatCannotBeReadSaysSoAndSendsNothing() {
        val uri = Uri.parse("content://docs/gone")
        launch(AnsweringRegistry { c, _ -> if (c is ActivityResultContracts.OpenDocument) uri else null })
        attach("File")
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Couldn't read that file.")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(emptyList(), store.sent)
    }

    @Test fun thereIsNoCameraRowOnAPhoneWithoutACamera() {
        launch(AnsweringRegistry { _, _ -> null })
        rule.onNodeWithContentDescription("Attach").performClick()
        rule.onNodeWithText("Photo").assertIsDisplayed()
        rule.onNodeWithText("Camera").assertDoesNotExist()
    }

    @Test fun refusingTheCameraPermissionSaysSoAndLeavesTheChatUsable() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        val registry = AnsweringRegistry { c, _ -> if (c is ActivityResultContracts.RequestPermission) false else null }
        launch(registry)
        attach("Camera")
        assertEquals(Manifest.permission.CAMERA, registry.launched.single().second)
        rule.onNodeWithText("Camera access is off. Turn it on in Settings to take photos.").assertIsDisplayed()
        assertEquals(emptyList(), store.sent)
        // Still usable: the attach button works again.
        rule.onNodeWithContentDescription("Attach").assertIsDisplayed()
    }

    @Test fun grantingTheCameraPermissionOpensTheCameraOnOneFileOfOurOwn() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val registry = AnsweringRegistry { c, input ->
            if (c is ActivityResultContracts.TakePicture) {
                cameraFileOf(input as Uri).also { it.parentFile!!.mkdirs() }.writeBytes(ByteArray(10) { 1 })
                true
            } else null
        }
        launch(registry)
        attach("Camera")
        rule.waitUntil(5_000) { store.sent.isNotEmpty() }
        val uri = registry.launched.single().second as Uri
        assertEquals(SharedFiles.authority(app), uri.authority)
        val (path, kind) = store.sent.single()
        assertEquals(MediaKind.Photo, kind)
        assertEquals(cameraFileOf(uri).path, path)
    }

    @Test fun aCancelledCameraSendsNothingAndLeavesNoFile() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val registry = AnsweringRegistry { c, input ->
            if (c is ActivityResultContracts.TakePicture) {
                cameraFileOf(input as Uri).also { it.parentFile!!.mkdirs() }.writeBytes(ByteArray(10))
                false
            } else null
        }
        launch(registry)
        attach("Camera")
        rule.waitForIdle()
        Thread.sleep(200)
        rule.waitForIdle()
        assertEquals(emptyList(), store.sent)
        assertTrue(SharedFiles.cameraDir(app).listFiles().orEmpty().isEmpty())
    }

    @Test fun refusingTheMicrophoneSaysSoAndLeavesTheChatUsable() {
        val registry = AnsweringRegistry { c, _ -> if (c is ActivityResultContracts.RequestPermission) false else null }
        launch(registry)
        rule.onNodeWithContentDescription("Record voice message").performClick()
        assertEquals(Manifest.permission.RECORD_AUDIO, registry.launched.single().second)
        rule.onNodeWithText("Microphone access is off. Turn it on in Settings to record voice messages.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Record voice message").assertIsDisplayed()
        assertEquals(emptyList(), store.sent)
    }

    @Test fun theRecordingBarShowsTimeCancelAndSend() {
        var cancels = 0
        var sends = 0
        rule.setContent {
            AppTheme {
                Composer(
                    enabled = true, text = "", onTextChange = {}, onSend = {}, onAttach = {}, onRecordVoice = {},
                    recording = org.example.app.ui.media.CaptureState.Recording(65.4),
                    onCancelRecording = { cancels++ }, onSendRecording = { sends++ },
                )
            }
        }
        rule.onNodeWithText("Recording…").assertIsDisplayed()
        rule.onNodeWithText("1:05").assertIsDisplayed()
        // The message field is gone while recording.
        rule.onNodeWithText("Message").assertDoesNotExist()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithContentDescription("Send").performClick()
        assertEquals(1, cancels)
        assertEquals(1, sends)
    }

    @Test fun theRecordingBarWaitsWhileTheNoteIsBeingFinished() {
        rule.setContent {
            AppTheme {
                Composer(
                    enabled = true, text = "", onTextChange = {}, onSend = {}, onAttach = {}, onRecordVoice = {},
                    recording = org.example.app.ui.media.CaptureState.Recording(3.0, stopping = true),
                )
            }
        }
        rule.onNodeWithText("Cancel").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }
}
