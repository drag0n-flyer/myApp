package org.example.app.live

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import org.example.app.contracts.SecretNames
import org.example.app.contracts.SecretStore

/**
 * The account's data on this device and its crash-safe wipe (mirrors the iPhone's `LocalAccountData`).
 *
 * A wipe first writes a durable marker ([markWipePending]), then deletes step by step; the marker is removed only when
 * everything is gone. A kill or a failed step leaves the marker, and the app finishes the wipe at the next launch or
 * before the next sign-in, before any data is opened. While it is there no database key is read or made.
 *
 * [owner] records whose data this is (`accountId/deviceId`, IDs only): a sign-in as anyone else wipes the data first.
 */
class LocalData(private val context: Context, private val platform: LivePlatform, private val secrets: () -> SecretStore) {
    private val marker = File(platform.filesDir, "wipe.pending")
    private val owner = File(platform.filesDir, "data.owner")

    /** Kinds of wipe: [FULL] signs out and deletes every secret and the Keystore key; [DATA] keeps the session. */
    companion object {
        const val FULL = "full"
        const val DATA = "data"
    }

    /** The pending wipe's kind, or null when none is pending. */
    val pending: String? get() = if (marker.exists()) runCatching { marker.readText().trim() }.getOrNull()?.takeIf { it == DATA } ?: FULL else null

    fun markWipePending(kind: String) {
        if (pending == FULL) return // never downgrade a full wipe
        platform.filesDir.mkdirs()
        val temp = File(platform.filesDir, "wipe.pending.tmp")
        FileOutputStream(temp).use { out ->
            out.write(kind.toByteArray())
            out.fd.sync()
        }
        if (!temp.renameTo(marker)) error("wipe marker not written")
    }

    /** True when the data on disk belongs to [identity] or to nobody yet. */
    fun ownedBy(identity: String): Boolean = !owner.exists() || runCatching { owner.readText() }.getOrNull() == identity

    fun recordOwner(identity: String) {
        platform.filesDir.mkdirs()
        owner.writeText(identity)
    }

    /**
     * Deletes the files and secrets. Every step runs; the first failure is returned. The marker goes only when no step
     * failed and nothing is left.
     */
    fun deleteAll(kind: String): Exception? {
        var failure: Exception? = null
        fun step(body: () -> Unit) = try { body() } catch (e: Exception) { failure = failure ?: e }
        step { platform.cancelNotifications() }
        step { if (!context.deleteDatabase(platform.databaseName) && databaseFile().exists()) error("database not deleted") }
        for (dir in dirs()) step { if (dir.exists() && !dir.deleteRecursively()) error("${dir.name} not deleted") }
        step { if (owner.exists() && !owner.delete()) error("owner not deleted") }
        if (kind == FULL) {
            step { secrets().wipeAll() } // the session, the database key, the signing key and the Keystore key
        } else {
            step { secrets().delete(SecretNames.DB_KEY) }
            step { secrets().delete(SecretNames.MLS_SIGNING) }
        }
        val left = databaseFile().exists() || dirs().any { it.exists() } ||
            (kind == FULL && !platform.secretsDir.listFiles().isNullOrEmpty())
        if (failure == null && !left) step { if (!marker.delete() && marker.exists()) error("marker not deleted") }
        else if (failure == null) failure = IllegalStateException("data left after the wipe")
        return failure
    }

    private fun databaseFile() = context.getDatabasePath(platform.databaseName)

    private fun dirs() = listOf(platform.mlsDir, platform.mediaDir, File(platform.cacheDir, "open"), File(platform.cacheDir, "outgoing"))
}
