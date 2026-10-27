package org.example.app.auth

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
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
 * Because the key is not auth-bound, removing the screen lock does not normally invalidate it; if the
 * Keystore does report it as gone or permanently invalidated, [KeyLostException] is thrown.
 */
class KeystoreKey : WrappingKeyVault {
    @Volatile private var backingKind: String? = null

    /** `"strongbox"` or `"tee"`: where the key lives. Reads the existing key's security level, or makes the key. */
    override val backing: String
        get() = backingKind ?: synchronized(this) { backingKind ?: (existingKey() ?: createKey()).let { backingKind!! } }

    fun backing(): String = backing

    @Synchronized
    override fun encryptCipher(): Cipher {
        val key = existingKey() ?: createKey()
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyLostException("Key invalidated", e)
        }
    }

    @Synchronized
    override fun decryptCipher(nonce: ByteArray): Cipher {
        val key = existingKey() ?: throw KeyLostException("Key missing")
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyLostException("Key invalidated", e)
        }
    }

    @Synchronized
    override fun hasKey(): Boolean = keyStore().containsAlias(ALIAS)

    @Synchronized
    override fun deleteKey() {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        backingKind = null
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existingKey(): SecretKey? = try {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.also { if (backingKind == null) backingKind = levelOf(it) }
    } catch (e: UnrecoverableKeyException) {
        throw KeyLostException("Key unrecoverable", e)
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw KeyLostException("Key invalidated", e)
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

    private fun createKey(): SecretKey {
        return try {
            generate(strongBox = true).also { backingKind = STRONGBOX }
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false).also { backingKind = TEE }
        }
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
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
