package org.example.app.auth

import java.io.File
import java.io.IOException
import javax.crypto.Cipher
import org.example.app.contracts.SecretStore

/** The wrapping key is gone or permanently unusable (for example the Keystore invalidated it). */
class KeyLostException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The one AES-256-GCM wrapping key. The real one lives in the Android Keystore ([KeystoreKey]);
 * unit tests use a software stand-in, because Robolectric has no real Keystore.
 */
interface WrappingKeyVault {
    /** `"strongbox"` or `"tee"`. */
    val backing: String

    /** A GCM encrypt cipher under the key, made if absent. The cipher picks its own random nonce (`iv`). */
    fun encryptCipher(): Cipher

    /** A GCM decrypt cipher (128-bit tag) with [nonce]. Throws [KeyLostException] if the key is gone or invalidated. */
    fun decryptCipher(nonce: ByteArray): Cipher

    /** Whether the key exists right now. */
    fun hasKey(): Boolean

    /** Deletes the key. No-op if absent. */
    fun deleteKey()
}

/**
 * File format: `nonce (12) || ciphertext || tag (16)`, AAD = the UTF-8 secret name, one file per secret.
 * Unwrapped values are cached in memory for the process lifetime (StrongBox calls are slow).
 *
 * If the key is lost, every file is unreadable: they are dropped and reads return `null`, so the app signs
 * in afresh (the iPhone store likewise drops an item it can never read). A file that fails authentication
 * with a live key (swapped, truncated, tampered) throws instead, and is left in place.
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
        require(raw.size >= NONCE_BYTES + TAG_BYTES) { "Stored secret is too short" }
        val plain = try {
            val cipher = vault.decryptCipher(raw.copyOfRange(0, NONCE_BYTES))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            cipher.doFinal(raw, NONCE_BYTES, raw.size - NONCE_BYTES)
        } catch (_: KeyLostException) {
            wipeAllLocked()
            return null
        }
        logBackingOnce()
        cache[name] = plain
        return plain.copyOf()
    }

    @Synchronized
    override fun save(name: String, value: ByteArray) {
        checkName(name)
        // Files left over from a key that no longer exists can never be opened: drop them before a new key starts.
        if (!vault.hasKey()) dir.listFiles()?.forEach { it.delete() }
        val sealed = try {
            seal(name, value)
        } catch (_: KeyLostException) {
            wipeAllLocked() // the other files can no longer be opened
            seal(name, value)
        }
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(File(dir, name))) {
            tmp.delete()
            throw IOException("Could not store secret")
        }
        cache[name] = value.copyOf()
    }

    @Synchronized
    override fun delete(name: String) {
        checkName(name)
        cache.remove(name)?.fill(0)
        File(dir, name).delete()
    }

    @Synchronized
    override fun wipeAll() = wipeAllLocked()

    private fun wipeAllLocked() {
        cache.values.forEach { it.fill(0) }
        cache.clear()
        dir.listFiles()?.forEach { it.delete() }
        vault.deleteKey()
    }

    private fun seal(name: String, value: ByteArray): ByteArray {
        val cipher = vault.encryptCipher()
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(value)
        val nonce = cipher.iv
        check(nonce.size == NONCE_BYTES) { "Unexpected nonce length" }
        logBackingOnce()
        return nonce + ct
    }

    private fun logBackingOnce() {
        if (!logged) {
            logged = true
            log("keystore=${vault.backing}")
        }
    }

    private fun checkName(name: String) {
        require(name.isNotEmpty() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == '\u0000' }) { "Invalid secret name" }
    }

    private companion object {
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
    }
}
