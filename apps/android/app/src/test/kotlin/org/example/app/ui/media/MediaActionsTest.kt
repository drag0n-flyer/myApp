package org.example.app.ui.media

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.example.app.R
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaActionsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = java.nio.file.Files.createTempDirectory("actions").toFile()
    private val notices = java.util.Collections.synchronizedList(mutableListOf<Int>())

    @After fun stop() = scope.cancel()

    // FileProvider remembers the folders it serves for the whole process; every Robolectric test has a cache folder of its own.
    @Before fun forgetProviderFolders() {
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        cache.isAccessible = true
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    private fun plain(name: String, text: String = "data") = File(dir, name).also { it.writeText(text) }

    private fun await(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    private fun actions(opener: AttachmentOpener?, context: Context = app) = MediaActions(context, opener, scope) { notices += it }

    @Test fun openingAFileStartsTheViewerForOneUriAndDeletesThePlaintext() {
        val opener = FakeOpener(mapOf("m1" to plain("plain-1", "pdf-bytes")))
        actions(opener).openFile("m1", "../../report.pdf")
        await("the viewer") { shadowOf(app).peekNextStartedActivity() != null }
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/pdf", intent.type)
        assertEquals(SharedFiles.authority(app), intent.data!!.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // The hostile name became a plain one, inside the share folder.
        val staged = File(SharedFiles.shareDir(app).listFiles()!!.single(), "report.pdf")
        assertEquals("pdf-bytes", staged.readText())
        assertEquals(1, opener.closes)
        assertTrue(notices.isEmpty())
    }

    @Test fun sharingAFileOpensTheShareSheetWithASendIntent() {
        actions(FakeOpener(mapOf("m1" to plain("plain-1")))).shareFile("m1", "notes.txt")
        await("the share sheet") { shadowOf(app).peekNextStartedActivity() != null }
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test fun sharingAPhotoNamesItFromWhatItIs() {
        val png = File(dir, "photo-bytes").also {
            val bmp = android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888)
            it.outputStream().use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
        }
        actions(FakeOpener(mapOf("m1" to png))).sharePhoto("m1")
        await("the share sheet") { shadowOf(app).peekNextStartedActivity() != null }
        val staged = SharedFiles.shareDir(app).listFiles()!!.single().listFiles()!!.single()
        assertTrue(staged.name.startsWith("photo."), staged.name)
    }

    @Test fun whenNoAppCanOpenItTheUserIsToldAndNothingCrashes() {
        val refusing = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) = throw ActivityNotFoundException("none")
        }
        actions(FakeOpener(mapOf("m1" to plain("plain-1"))), refusing).openFile("m1", "x.pdf")
        await("the notice") { notices.isNotEmpty() }
        assertEquals(listOf(R.string.media_no_app), notices.toList())
    }

    @Test fun anInstallablePackageIsNeverHandedOn() {
        actions(FakeOpener(mapOf("m1" to plain("plain-1")))).openFile("m1", "game.apk")
        await("the notice") { notices.isNotEmpty() }
        assertEquals(listOf(R.string.media_no_app), notices.toList())
        assertEquals(null, shadowOf(app).peekNextStartedActivity())
    }

    @Test fun anAttachmentThatCannotBeOpenedIsReported() {
        actions(FakeOpener(emptyMap())).openFile("m1", "x.pdf")
        await("the notice") { notices.isNotEmpty() }
        assertEquals(listOf(R.string.media_couldnt_download), notices.toList())
        actions(null).shareFile("m1", "x.pdf")
        await("the second notice") { notices.size == 2 }
    }

    @Test fun aSecondTapWhileTheFirstIsPreparingDoesNothing() {
        val slow = object : AttachmentOpener {
            var opens = 0
            val gate = java.util.concurrent.CountDownLatch(1)
            override suspend fun open(messageId: String): OpenedMedia? {
                opens++
                gate.await()
                return object : OpenedMedia {
                    override val file = plain("plain-1")
                    override fun close() {}
                }
            }
        }
        val a = actions(slow)
        a.openFile("m1", "x.pdf")
        await("the first open") { slow.opens == 1 }
        a.openFile("m1", "x.pdf")
        slow.gate.countDown()
        await("the viewer") { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals(1, slow.opens)
        assertNotNull(shadowOf(app).nextStartedActivity)
    }
}
