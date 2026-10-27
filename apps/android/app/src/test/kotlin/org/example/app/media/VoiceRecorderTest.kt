package org.example.app.media

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceRecorderTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun startWithoutMicrophonePermissionFailsWithPermission() {
        val dir = File.createTempFile("rec", "").also { it.delete() }
        val recorder = VoiceRecorder(app, dir)
        val r = assertIs<StartResult.Failed>(recorder.start())
        assertEquals(VoiceFailure.Permission, r.reason)
        assertFalse(recorder.isRecording)
        assertFalse(dir.exists() && dir.list()!!.isNotEmpty())
    }

    @Test fun stopWithoutStartIsATypedFailureNotNull() {
        val r = VoiceRecorder(app, File("unused")).stop()
        assertIs<StopResult.Failed>(r)
    }

    @Test fun manifestDeclaresRecordAudio() {
        val info = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions!!.contains(Manifest.permission.RECORD_AUDIO))
    }

    @Test fun mediaCacheSweepRemovesOnlyItsTemporaryDirectories() {
        val open = File(app.cacheDir, "open").also { it.mkdirs(); File(it, "a").writeText("x") }
        val out = File(app.cacheDir, "outgoing").also { it.mkdirs(); File(it, "b.ogg").writeText("x") }
        val other = File(app.cacheDir, "keep").also { it.mkdirs(); File(it, "c").writeText("x") }
        MediaCache.sweep(app)
        assertEquals(0, open.list()!!.size)
        assertEquals(0, out.list()!!.size)
        assertEquals(1, other.list()!!.size)
        assertEquals(open, MediaCache.openDir(app))
        assertEquals(out, MediaCache.outgoingDir(app))
    }
}
