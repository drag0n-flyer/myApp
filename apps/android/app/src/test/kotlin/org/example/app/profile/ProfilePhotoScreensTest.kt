package org.example.app.profile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.app.core.chats.Contact
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.core.people.Profile
import org.example.app.ui.AVATAR_PHOTO_TAG
import org.example.app.ui.Avatar
import org.example.app.ui.chatinfo.ChatInfoScreen
import org.example.app.ui.chatlist.ChatListScreen
import org.example.app.ui.chatlist.ChatListViewModel
import org.example.app.ui.settings.AccountActions
import org.example.app.ui.settings.ProfileScreen
import org.example.app.ui.settings.ProfileViewModel
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Sample mode: a contact with a photo shows it in the chat list and chat info; the profile screen offers "Change photo" (task P5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilePhotoScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun store(): SampleChatStore {
        val s = SampleChatStore(SampleData.make(Instant.now()))
        val riya = s.chat("chat-riya")!!.contactId
        s.putProfile(Profile(riya, "Riya", requireNotNull(SamplePhoto.make()), 1))
        return s
    }

    private fun waitForPhotos(min: Int) = rule.waitUntil(10_000) { rule.onAllNodesWithTag(AVATAR_PHOTO_TAG, useUnmergedTree = true).fetchSemanticsNodes().size >= min }

    @Test fun theSamplePhotoIsAJpegThatPassesTheRule() {
        assertTrue(ProfilePhoto.decodes(requireNotNull(SamplePhoto.make())))
    }

    @Test fun theChatListShowsTheContactsPhoto() {
        val s = store()
        val vm = ChatListViewModel(
            s, org.example.app.core.settings.ServerPolicy(org.example.app.core.calls.ModePreference.Auto, org.example.app.core.calls.DataMode.High, org.example.app.core.settings.IPHiding.NonContacts, true, "home.example.org"),
            kotlinx.coroutines.flow.MutableStateFlow(org.example.app.core.settings.UserSettings()), org.example.app.core.calls.NetworkType.Wifi,
        )
        rule.setContent {
            CompositionLocalProvider(LocalProfilePhotos provides s.profiles) {
                AppTheme { ChatListScreen(vm, onOpenChat = {}, onOpenRequests = {}, onNewChat = {}, onSearch = {}) }
            }
        }
        waitForPhotos(1)
        assertEquals(1, rule.onAllNodesWithTag(AVATAR_PHOTO_TAG, useUnmergedTree = true).fetchSemanticsNodes().size, "only the contact with a photo")
        rule.onNodeWithText("Sam").assertIsDisplayed() // the others keep their letter tile
    }

    @Test fun chatInfoShowsTheContactsPhoto() {
        val s = store()
        rule.setContent {
            CompositionLocalProvider(LocalProfilePhotos provides s.profiles) {
                AppTheme { ChatInfoScreen(s, "chat-riya", onBack = {}, onCall = {}, onSearch = {}, onBlocked = {}) }
            }
        }
        waitForPhotos(1)
    }

    @Test fun aPhotoThatDoesNotDecodeFallsBackToTheLetter() {
        val hostile = byteArrayOf(-1, -40, -1, -64, 0, 11, 8, -2, -24, -2, -24, 1, 1, 0x11, 0) // declares 65000 x 65000
        val p = kotlinx.coroutines.flow.MutableStateFlow(mapOf("acc" to Profile("acc", "Ann", hostile, 1)))
        rule.setContent { CompositionLocalProvider(LocalProfilePhotos provides p) { AppTheme { Avatar("Ann", colorKey = "acc") } } }
        rule.waitForIdle()
        Thread.sleep(300)
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag(AVATAR_PHOTO_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun theDecodedPhotoCacheIsClearedOnRequest() {
        val bytes = requireNotNull(SamplePhoto.make())
        assertNotNull(ProfilePhotoCache.get(bytes))
        assertTrue(ProfilePhotoCache.size >= 1)
        ProfilePhotoCache.clear()
        assertEquals(0, ProfilePhotoCache.size)
    }

    @Test fun theProfileScreenOffersChangePhotoAndRemoveOnlyWithAPhoto() {
        val removed = mutableListOf<ByteArray?>()
        val vm = ProfileViewModel(AccountActions(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), {}, {})) { removed += it }
        val own = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Profile>>(emptyMap())
        rule.setContent {
            CompositionLocalProvider(LocalProfilePhotos provides own) {
                AppTheme { ProfileScreen(Contact("me", "Ana", "ana"), vm, onBack = {}) }
            }
        }
        rule.onNodeWithText("Change photo").assertIsDisplayed()
        rule.onNodeWithText("Remove photo").assertDoesNotExist()
        own.value = mapOf("me" to Profile("me", "Ana", requireNotNull(SamplePhoto.make()), 1))
        rule.waitForIdle()
        rule.onNodeWithText("Remove photo").assertIsDisplayed().performClick()
        assertEquals(listOf<ByteArray?>(null), removed)
    }

    // Review fixes: the cache key cannot be collided, and a decode that outlives a sign-out does not refill the cache.

    @Test fun twoPhotosWithTheSameHashAndSizeAreDifferentCacheEntries() {
        ProfilePhotoCache.clear()
        val a = ByteArray(40) { (it + 3).toByte() }
        val b = a.copyOf().also { it[0] = (it[0] + 1).toByte(); it[1] = (it[1] - 31).toByte() }
        assertTrue(a.contentHashCode() == b.contentHashCode() && a.size == b.size && !a.contentEquals(b), "fixture must collide")
        var decodes = 0
        val first = ProfilePhotoCache.get(a) { decodes++; android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888) }
        val second = ProfilePhotoCache.get(b) { decodes++; android.graphics.Bitmap.createBitmap(3, 3, android.graphics.Bitmap.Config.ARGB_8888) }
        assertEquals(2, decodes)
        assertTrue(first !== second && second!!.width == 3)
        assertEquals(2, ProfilePhotoCache.size)
        ProfilePhotoCache.get(a) { error("a hit must not decode") }
        ProfilePhotoCache.clear()
    }

    @Test fun aDecodeThatFinishesAfterClearDoesNotRefillTheCache() {
        ProfilePhotoCache.clear()
        val bytes = ByteArray(40) { it.toByte() }
        val result = ProfilePhotoCache.get(bytes) {
            ProfilePhotoCache.clear() // the sign-out happens while the decode is running
            android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        }
        assertNotNull(result, "the caller of the old session still gets its picture")
        assertEquals(0, ProfilePhotoCache.size)
    }
}
