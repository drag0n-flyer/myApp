package org.example.app.ui.media

import java.io.Closeable
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.example.app.messaging.media.OpenedAttachment

/** A decrypted attachment on disk. [close] deletes the plaintext: close it as soon as the bytes have been read or copied. */
interface OpenedMedia : Closeable {
    val file: File
}

/**
 * Decrypts a downloaded attachment for viewing. A message's `MediaState.Phase.Local` path is the still encrypted download:
 * only this gives a file the platform can decode or play. Null when the attachment is not there, did not decrypt, or
 * there is no signed-in session.
 */
fun interface AttachmentOpener {
    suspend fun open(messageId: String): OpenedMedia?
}

/**
 * Opens off the main thread and never leaves plaintext behind: the decrypt is blocking and cannot be stopped half way, so it
 * is allowed to finish, and when the caller was cancelled meanwhile the result is closed (deleted) here instead of lost.
 * Failure of any kind is null.
 */
suspend fun AttachmentOpener.openOrNull(messageId: String, io: CoroutineDispatcher = Dispatchers.IO): OpenedMedia? {
    // The result is kept here as well as returned: when the caller is cancelled, withContext throws and drops its value.
    val held = java.util.concurrent.atomic.AtomicReference<OpenedMedia?>(null)
    val opened = try {
        withContext(io + NonCancellable) { open(messageId).also { held.set(it) } }
    } catch (e: CancellationException) {
        held.get()?.close()
        throw e
    } catch (e: Exception) {
        null
    }
    if (opened != null && !coroutineContext.isActive) {
        opened.close()
        throw CancellationException("cancelled while the attachment was being opened")
    }
    return opened
}

/** The engine's decrypted attachment as an [OpenedMedia]; closing it deletes the plaintext. */
fun OpenedAttachment.asOpenedMedia(): OpenedMedia = object : OpenedMedia {
    override val file: File get() = this@asOpenedMedia.file
    override fun close() = this@asOpenedMedia.close()
}
