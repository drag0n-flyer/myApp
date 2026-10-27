package org.example.app.auth

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import org.example.app.contracts.SecretStore
import org.example.app.contracts.SecretStoreUnavailable
import org.example.app.contracts.SecretsLost

/**
 * The one AES-256-GCM wrapping key. The real one lives in the Android Keystore ([KeystoreKey]);
 * unit tests use a software stand-in, because Robolectric has no real Keystore.
 */
interface WrappingKeyVault {
    /** `"strongbox"` or `"tee"`. */
    val backing: String

    /**
     * A GCM encrypt cipher under the existing key; never makes a key. The cipher picks its own random nonce (`iv`).
     * Throws [SecretStoreUnavailable] on any Keystore failure, [SecretsLost] if the key is permanently invalidated.
     */
    fun encryptCipher(): Cipher

    /** A GCM decrypt cipher (128-bit tag) with [nonce]. Throws [SecretsLost] only on proven loss, otherwise [SecretStoreUnavailable]. */
    fun decryptCipher(nonce: ByteArray): Cipher

    /** `false` only when the Keystore cleanly reports the alias absent. Throws [SecretStoreUnavailable] on any error. */
    fun hasKey(): Boolean

    /** Makes the key. A no-op if the alias already exists: it must never replace a key. */
    fun createKey()

    /** Deletes the key. No-op if absent. */
    fun deleteKey()
}

/**
 * File format: `version (1) || nonce (12) || ciphertext || tag (16)`, AAD = the UTF-8 secret name, one file per
 * secret. Unwrapped values are cached in memory for the process lifetime (StrongBox calls are slow).
 *
 * Errors never cause deletion. Any Keystore failure throws [SecretStoreUnavailable] and the caller retries.
 * The key counts as lost only when the Keystore cleanly reports the alias absent (or permanently invalidated)
 * while secret files exist: [SecretsLost] is thrown, and the app layer runs the full wipe and sign-in flow.
 * A key is created only when no secret file exists, so a wrong "absent" answer can never replace a live key.
 * [load] returns `null` only for a secret that was never saved. A file that fails authentication with a live
 * key (swapped, truncated, tampered) throws and is left in place.
 */
class VaultSecretStore(
    private val vault: WrappingKeyVault,
    private val dir: File,
    private val log: (String) -> Unit,
) : SecretStore {
    private val cache = HashMap<String, ByteArray>()
    private var logged = false

    @Synchronized
    override fun load(name: String): ByteArray? {
        checkName(name)
        cache[name]?.let { return it.copyOf() }
        val file = File(dir, name)
        if (!file.isFile) return null
        val raw = file.readBytes()
        if (raw.size < HEADER_BYTES + TAG_BYTES) throw IOException("Stored secret is too short")
        if (raw[0] != VERSION) throw IOException("Unsupported secret format")
        val cipher = vault.decryptCipher(raw.copyOfRange(1, HEADER_BYTES))
        val plain = cipherCall {
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            cipher.doFinal(raw, HEADER_BYTES, raw.size - HEADER_BYTES)
        }
        logBackingOnce()
        cache[name] = plain
        return plain.copyOf()
    }

    @Synchronized
    override fun save(name: String, value: ByteArray) {
        checkName(name)
        if (!vault.hasKey()) {
            // Never replace a key under existing files, and never delete them: report the loss.
            if (secretFiles().isNotEmpty()) throw SecretsLost("Wrapping key is gone")
            vault.createKey()
        }
        val sealed = seal(name, value)
        writeDurably(name, sealed)
        cache[name]?.fill(0)
        cache[name] = value.copyOf()
    }

    @Synchronized
    override fun delete(name: String) {
        checkName(name)
        cache.remove(name)?.fill(0)
        val file = File(dir, name)
        if (!file.delete() && file.exists()) throw IOException("Could not delete secret")
    }

    @Synchronized
    override fun wipeAll() {
        cache.values.forEach { it.fill(0) }
        cache.clear()
        var failed = false
        dir.listFiles()?.forEach { if (!it.delete() && it.exists()) failed = true }
        vault.deleteKey() // without the key any file left behind is unreadable
        if (failed) throw IOException("Could not delete every secret file")
    }

    private fun secretFiles(): List<File> {
        val all = dir.listFiles()
        if (all == null) {
            if (dir.exists()) throw SecretStoreUnavailable("Cannot list secrets", null)
            return emptyList()
        }
        return all.filter { !it.name.startsWith(TEMP_PREFIX) }
    }

    private fun seal(name: String, value: ByteArray): ByteArray {
        val cipher = vault.encryptCipher()
        val ct = cipherCall {
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            cipher.doFinal(value)
        }
        val nonce = cipher.iv
        check(nonce.size == NONCE_BYTES) { "Unexpected nonce length" }
        logBackingOnce()
        return byteArrayOf(VERSION) + nonce + ct
    }

    /**
     * Temp file, fsync, atomic rename, then fsync the directory ([syncDirectory], best effort).
     */
    private fun writeDurably(name: String, sealed: ByteArray) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Could not create secrets directory")
        val tmp = File(dir, TEMP_PREFIX + name)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(sealed)
                out.flush()
                out.fd.sync()
            }
            Files.move(tmp.toPath(), File(dir, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        syncDirectory(dir)
    }

    /**
     * The crypto runs inside the keystore daemon at `doFinal`, so a transient daemon failure surfaces there.
     * [AEADBadTagException] means the item failed authentication (corrupt) and passes through; every other
     * cipher-side failure is [SecretStoreUnavailable].
     */
    private inline fun <T> cipherCall(block: () -> T): T = try {
        block()
    } catch (e: AEADBadTagException) {
        throw e
    } catch (e: SecretStoreUnavailable) {
        throw e
    } catch (e: SecretsLost) {
        throw e
    } catch (e: GeneralSecurityException) { // IllegalBlockSize, other BadPadding, ...
        throw SecretStoreUnavailable("Keystore cipher failure", e)
    } catch (e: ProviderException) {
        throw SecretStoreUnavailable("Keystore provider failure", e)
    }

    private fun logBackingOnce() {
        if (!logged) {
            logged = true
            log("keystore=${vault.backing}")
        }
    }

    private fun checkName(name: String) {
        require(name.isNotEmpty() && name != "." && name != ".." && !name.startsWith(TEMP_PREFIX) &&
            name.none { it == '/' || it == '\\' || it == '\u0000' }) { "Invalid secret name" }
    }

    private companion object {
        const val VERSION: Byte = 1
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        const val HEADER_BYTES = 1 + NONCE_BYTES
        /** Secret names may not start with this, so a temp file never collides with a secret. */
        const val TEMP_PREFIX = "~tmp~"
    }
}

/**
 * Best-effort fsync of a directory, so a rename inside it survives power loss. A directory cannot be opened
 * with `FileInputStream` (the JDK and Android libcore both refuse with EISDIR), so this opens a read-only
 * [FileChannel] on it and calls `force`. Returns whether the sync happened; some filesystems refuse it, and
 * the rename is already atomic without it.
 */
internal fun syncDirectory(dir: File): Boolean = try {
    FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    true
} catch (_: IOException) {
    false
} catch (_: UnsupportedOperationException) {
    false
} catch (_: SecurityException) {
    false
}
