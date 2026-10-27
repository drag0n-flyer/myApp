package org.example.app.auth

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.ProviderException
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * The wrapping key in the Android Keystore, alias `app.secrets.v1`: AES-256-GCM, random nonces required,
 * not exportable, not bound to user authentication and `setUnlockedDeviceRequired(false)`, because push
 * handling must open the database while the phone is locked. StrongBox first, otherwise the TEE.
 *
 * Error mapping: only a clean `containsAlias == false` or [KeyPermanentlyInvalidatedException] is proof of
 * loss ([SecretsLost]). Every other failure, including a null key for an alias that exists and
 * [UnrecoverableKeyException], is [SecretStoreUnavailable]: retry later, delete nothing.
 */
class KeystoreKey : WrappingKeyVault {
    @Volatile private var backingKind: String? = null

    /** `"strongbox"`, `"tee"`, or `"unknown"` before a key exists. Reads only; never makes a key. */
    override val backing: String
        get() = backingKind ?: try {
            synchronized(this) { currentKeyOrNull()?.let { levelOf(it) } ?: UNKNOWN }
        } catch (_: SecretStoreUnavailable) {
            UNKNOWN
        }

    fun backing(): String = backing

    @Synchronized
    override fun encryptCipher(): Cipher {
        val key = currentKeyOrNull() ?: throw SecretStoreUnavailable("Key missing", null)
        return mapped { Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) } }
    }

    @Synchronized
    override fun decryptCipher(nonce: ByteArray): Cipher {
        val key = currentKeyOrNull() ?: throw SecretsLost("Key missing")
        return mapped { Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce)) } }
    }

    @Synchronized
    override fun hasKey(): Boolean = mapped { keyStore().containsAlias(ALIAS) }

    @Synchronized
    override fun createKey() {
        mapped {
            if (keyStore().containsAlias(ALIAS)) return@mapped
            val (_, level) = generateWithFallback(
                generate = { strongBox -> generate(strongBox) },
                cleanup = { try { keyStore().deleteEntry(ALIAS) } catch (_: Exception) {} },
            )
            backingKind = level
        }
    }

    @Synchronized
    override fun deleteKey() {
        mapped {
            val ks = keyStore()
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        }
        backingKind = null
    }

    /**
     * The key if the alias exists; `null` only when `containsAlias` cleanly says no.
     * An alias that exists but yields no key is an ambiguous failure, so it throws [SecretStoreUnavailable].
     */
    private fun currentKeyOrNull(): SecretKey? = mapped {
        val ks = keyStore()
        if (!ks.containsAlias(ALIAS)) return@mapped null
        val key = ks.getKey(ALIAS, null) as? SecretKey
            ?: throw SecretStoreUnavailable("Key unreadable", null)
        key
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw SecretsLost("Key invalidated", e)
    } catch (e: SecretStoreUnavailable) {
        throw e
    } catch (e: SecretsLost) {
        throw e
    } catch (e: GeneralSecurityException) { // includes KeyStoreException and UnrecoverableKeyException
        throw SecretStoreUnavailable("Keystore failure", e)
    } catch (e: ProviderException) {
        throw SecretStoreUnavailable("Keystore provider failure", e)
    } catch (e: IOException) {
        throw SecretStoreUnavailable("Keystore load failure", e)
    }

    private fun levelOf(key: SecretKey): String = try {
        val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
            .getKeySpec(key, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
        // KeyInfo.securityLevel needs API 31. Before that a key found from an earlier process reads as "tee";
        // a key made in this process is recorded exactly. This value only feeds the one-line log.
        if (Build.VERSION.SDK_INT >= 31 && info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX) STRONGBOX else TEE
    } catch (_: GeneralSecurityException) {
        TEE
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .setUnlockedDeviceRequired(false)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    companion object {
        const val ALIAS = "app.secrets.v1"
        const val STRONGBOX = "strongbox"
        const val TEE = "tee"
        const val UNKNOWN = "unknown"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}

/**
 * Generates with StrongBox first. Any [ProviderException] (which includes `StrongBoxUnavailableException`)
 * from the StrongBox attempt falls back to the TEE after [cleanup] drops any partial alias. A failure of the
 * TEE attempt propagates. Returns the key and where it lives.
 */
internal fun <T> generateWithFallback(generate: (strongBox: Boolean) -> T, cleanup: () -> Unit): Pair<T, String> =
    try {
        generate(true) to KeystoreKey.STRONGBOX
    } catch (_: ProviderException) {
        cleanup()
        generate(false) to KeystoreKey.TEE
    }
