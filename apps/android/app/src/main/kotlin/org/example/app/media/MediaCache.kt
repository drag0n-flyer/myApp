package org.example.app.media

import android.content.Context
import java.io.File

/**
 * Where temporary media lives (Design 4). Everything here is plaintext and short-lived, so it sits in `cacheDir`
 * and is swept at app start, after a process death.
 */
object MediaCache {
    /** Decrypted attachments while they are shown. */
    fun openDir(context: Context): File = File(context.cacheDir, "open")

    /** Photos and voice notes prepared for sending, until they are encrypted and uploaded. */
    fun outgoingDir(context: Context): File = File(context.cacheDir, "outgoing")

    /** Deletes the contents of both directories. Call once at app start, before anything is shown or recorded. */
    fun sweep(context: Context) {
        for (d in listOf(openDir(context), outgoingDir(context))) d.listFiles()?.forEach { it.deleteRecursively() }
    }
}
