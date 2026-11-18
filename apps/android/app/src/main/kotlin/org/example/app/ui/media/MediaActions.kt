package org.example.app.ui.media

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.AndroidRuntimeException
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.app.R

/**
 * Opens a downloaded file with another app, or shares it, and shares a photo. The attachment is decrypted, copied to a
 * folder of its own under a cleaned-up name ([SharedFiles]) and the plaintext is deleted again at once; the other app
 * gets read access to that one copy. Everything runs in [scope], off the main thread, and is cancelled with it.
 * Call from the main thread. [notify] gets the text for a failure; nothing here throws to the caller.
 */
class MediaActions(
    private val context: Context,
    private val opener: AttachmentOpener?,
    private val scope: CoroutineScope,
    /** Called right before the viewer or share sheet is started (the app lock allows the person to come back from it). */
    private val expectReturn: () -> Unit = {},
    private val notify: (Int) -> Unit,
) {
    // Main thread only. A second tap while the first is still preparing the file does nothing.
    private var busy = false

    /** Opens the file with the system's viewer for its type. [name] is the sender's name for it; it is cleaned before use. */
    fun openFile(messageId: String, name: String) = hand(messageId, name, view = true)

    fun shareFile(messageId: String, name: String) = hand(messageId, name, view = false)

    fun sharePhoto(messageId: String) = hand(messageId, null, view = false)

    private fun hand(messageId: String, name: String?, view: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val opened = opener?.openOrNull(messageId)
                if (opened == null) {
                    notify(R.string.media_couldnt_download)
                    return@launch
                }
                val staged = try {
                    // A photo has no name of its own: the extension comes from what the bytes are.
                    val finalName = name ?: ("photo." + withContext(Dispatchers.IO) { imageExtension(opened.file) })
                    SharedFiles.stage(SharedFiles.shareDir(context), opened.file, finalName, "file")
                } catch (e: IOException) {
                    null
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { opened.close() }
                }
                if (staged == null) {
                    notify(R.string.media_couldnt_download)
                    return@launch
                }
                val uri = try {
                    SharedFiles.uriFor(context, staged)
                } catch (e: IllegalArgumentException) {
                    notify(R.string.media_couldnt_download)
                    return@launch
                }
                val intent = if (view) {
                    SharedFiles.viewIntent(uri, staged.name)
                } else {
                    SharedFiles.sendIntent(uri, staged.name)?.let { Intent.createChooser(it, null) }
                }
                if (intent == null) {
                    notify(R.string.media_no_app)
                    return@launch
                }
                // From the application context (not from the screen) an activity can only start in a task of its own.
                if (!context.isActivity()) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    expectReturn()
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    notify(R.string.media_no_app)
                } catch (e: SecurityException) {
                    notify(R.string.media_no_app)
                } catch (e: AndroidRuntimeException) {
                    notify(R.string.media_no_app)
                }
            } finally {
                busy = false
            }
        }
    }

    private companion object {
        fun Context.isActivity(): Boolean {
            var c: Context = this
            while (c is ContextWrapper) {
                if (c is Activity) return true
                c = c.baseContext
            }
            return false
        }

        fun imageExtension(file: File): String {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            return bounds.outMimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "jpg"
        }
    }
}
