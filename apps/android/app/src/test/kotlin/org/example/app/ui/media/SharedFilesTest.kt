package org.example.app.ui.media

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedFilesTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val root = java.nio.file.Files.createTempDirectory("shared").toFile()
    private val shareDir = File(root, "share")

    private fun source(text: String = "hello"): File = File(root, "plain-1").also { it.writeText(text) }

    private fun inside(file: File) = file.canonicalPath.startsWith(shareDir.canonicalPath + File.separator)

    @Test fun stageCopiesTheBytesUnderTheSafeName() = runBlocking {
        val staged = SharedFiles.stage(shareDir, source("abc"), "report.pdf", "file")
        assertEquals("report.pdf", staged.name)
        assertEquals("abc", staged.readText())
        assertTrue(inside(staged))
        // The plaintext source is the caller's to delete; staging must not move it.
        assertTrue(File(root, "plain-1").exists())
    }

    @Test fun aHostileNameCannotLeaveTheShareFolder() = runBlocking {
        for (raw in listOf("../../evil.sh", "..\\..\\evil.sh", "/etc/passwd", "..", "a/../../b")) {
            val staged = SharedFiles.stage(shareDir, source(), raw, "file")
            assertTrue(inside(staged), "raw=$raw staged=${staged.path}")
        }
        // Nothing was written next to the share folder.
        assertEquals(listOf("plain-1", "share"), root.list()!!.sorted())
    }

    @Test fun twoStagedFilesWithTheSameNameDoNotCollide() = runBlocking {
        val a = SharedFiles.stage(shareDir, source("one"), "x.txt", "file")
        val b = SharedFiles.stage(shareDir, source("two"), "x.txt", "file")
        assertEquals("one", a.readText())
        assertEquals("two", b.readText())
    }

    @Test fun oldStagedFilesAreRemovedAndRecentOnesKept() = runBlocking {
        val old = SharedFiles.stage(shareDir, source(), "old.txt", "file", now = 1_000L)
        old.parentFile!!.setLastModified(1_000L)
        val recentAt = 2_000L + SharedFiles.MAX_AGE_MS / 2
        val recent = SharedFiles.stage(shareDir, source(), "recent.txt", "file", now = recentAt)
        recent.parentFile!!.setLastModified(recentAt)
        val fresh = SharedFiles.stage(shareDir, source(), "fresh.txt", "file", now = 1_000L + SharedFiles.MAX_AGE_MS + 1)
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(fresh.exists())
    }

    @Test fun mimeTypeComesFromTheExtension() {
        assertEquals("application/pdf", SharedFiles.mimeFor("a.pdf"))
        assertEquals("image/jpeg", SharedFiles.mimeFor("a.JPG"))
        assertEquals("application/octet-stream", SharedFiles.mimeFor("a.zzzunknown"))
        assertEquals("application/octet-stream", SharedFiles.mimeFor("noextension"))
    }

    @Test fun viewIntentGrantsReadOnlyForTheOneUri() {
        val uri = Uri.parse("content://org.example.app.files/share/x/a.pdf")
        val intent = assertNotNull(SharedFiles.viewIntent(uri, "a.pdf"))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, intent.data)
        assertEquals("application/pdf", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNull(intent.component)
    }

    @Test fun anInstallablePackageIsNeverOffered() {
        assertNull(SharedFiles.viewIntent(Uri.parse("content://x/a.apk"), "a.apk"))
    }

    @Test fun sendIntentCarriesTheStreamAndGrantsReadOnly() {
        val uri = Uri.parse("content://org.example.app.files/share/x/a.png")
        val intent = SharedFiles.sendIntent(uri, "a.png")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        @Suppress("DEPRECATION")
        assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test fun theProviderServesAStagedFileAndNothingElse() = runBlocking {
        // FileProvider remembers the folders it serves for the whole process; every Robolectric test has a cache folder of its own.
        androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").also { it.isAccessible = true }
            .let { (it.get(null) as MutableMap<*, *>).clear() }
        val dir = SharedFiles.shareDir(context)
        val staged = SharedFiles.stage(dir, source("pdf"), "a.pdf", "file")
        val uri = SharedFiles.uriFor(context, staged)
        assertEquals(SharedFiles.authority(context), uri.authority)
        assertEquals("pdf", context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
        // A file in the same cache but outside the shared folders has no URI.
        val other = File(context.cacheDir, "open/secret.txt").also { it.parentFile!!.mkdirs(); it.writeText("no") }
        assertTrue(runCatching { SharedFiles.uriFor(context, other) }.isFailure)
    }
}
