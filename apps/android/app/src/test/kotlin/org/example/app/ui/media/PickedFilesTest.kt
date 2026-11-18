package org.example.app.ui.media

import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PickedFilesTest {
    private val out = File(java.nio.file.Files.createTempDirectory("picked").toFile(), "files")

    private fun inside(file: File) = file.canonicalPath.startsWith(out.canonicalPath + File.separator)

    private fun copied(r: PickedFiles.Result): File = assertIs<PickedFiles.Result.Copied>(r).file

    @Test fun theBytesAreCopiedUnderTheCleanedName() = runBlocking<Unit> {
        val file = copied(PickedFiles.copy(out, "My Report.pdf") { ByteArrayInputStream("abc".toByteArray()) })
        assertEquals("My Report.pdf", file.name)
        assertEquals("abc", file.readText())
        assertTrue(inside(file))
    }

    @Test fun aHostileNameStaysInsideTheFolder() = runBlocking<Unit> {
        for (raw in listOf("../../evil.sh", "..\\..\\evil.sh", "/data/data/app/x", "..", null, "")) {
            val file = copied(PickedFiles.copy(out, raw) { ByteArrayInputStream(ByteArray(3)) })
            assertTrue(inside(file), "raw=$raw file=${file.path}")
        }
    }

    @Test fun twoFilesWithTheSameNameDoNotCollide() = runBlocking<Unit> {
        val a = copied(PickedFiles.copy(out, "x.txt") { ByteArrayInputStream("one".toByteArray()) })
        val b = copied(PickedFiles.copy(out, "x.txt") { ByteArrayInputStream("two".toByteArray()) })
        assertEquals("one", a.readText())
        assertEquals("two", b.readText())
    }

    @Test fun aFileOverTheLimitIsRefusedAndNothingIsLeft() = runBlocking<Unit> {
        var read = 0L
        val endless = object : InputStream() {
            override fun read(): Int { read++; return 1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { read += len; return len }
        }
        assertEquals(PickedFiles.Result.TooBig, PickedFiles.copy(out, "endless.bin", maxBytes = 1_000) { endless })
        assertTrue(read < 1_000 + 64 * 1024, "read $read bytes of an endless stream with a cap of 1000")
        assertTrue(out.listFiles().orEmpty().isEmpty())
    }

    @Test fun aFileExactlyAtTheLimitIsKept() = runBlocking<Unit> {
        assertIs<PickedFiles.Result.Copied>(PickedFiles.copy(out, "ok.bin", maxBytes = 10) { ByteArrayInputStream(ByteArray(10)) })
    }

    @Test fun anUnreadableFileIsReportedAndNothingIsLeft() = runBlocking<Unit> {
        assertEquals(PickedFiles.Result.Unreadable, PickedFiles.copy(out, "a.txt") { null })
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("gone")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("gone")
        }
        assertEquals(PickedFiles.Result.Unreadable, PickedFiles.copy(out, "a.txt") { failing })
        assertTrue(out.listFiles().orEmpty().isEmpty())
    }

    @Test fun aCancelledCopyLeavesNothingBehind() = runBlocking<Unit> {
        val reading = CompletableDeferred<Unit>()
        val never = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reading.complete(Unit)
                Thread.sleep(20)
                return len
            }
        }
        val job = launch(Dispatchers.Default) { PickedFiles.copy(out, "endless.bin", maxBytes = Long.MAX_VALUE) { never } }
        kotlinx.coroutines.withTimeout(5_000) { reading.await() }
        job.cancel()
        job.join()
        assertTrue(out.listFiles().orEmpty().isEmpty(), out.listFiles().orEmpty().joinToString())
    }

    @Test fun onlyContentUrisFromThePickerAreAccepted() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(PickedFiles.isFromPicker(context, Uri.parse("content://com.android.providers.media.documents/document/1")))
        assertFalse(PickedFiles.isFromPicker(context, Uri.parse("file:///data/data/org.example.app/databases/app.db")))
        assertFalse(PickedFiles.isFromPicker(context, Uri.parse("/data/data/org.example.app/databases/app.db")))
        assertFalse(PickedFiles.isFromPicker(context, Uri.parse("http://example.com/x")))
        // The app's own FileProvider (the copies it shares and the camera file) is not a source to attach from.
        assertFalse(PickedFiles.isFromPicker(context, Uri.parse("content://${SharedFiles.authority(context)}/shared/open/x.pdf")))
    }
}
