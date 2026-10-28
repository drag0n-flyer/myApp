package org.example.app.auth

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.app.contracts.SecretStoreUnavailable
import org.example.app.contracts.SecretsLost

/** A keystore whose answers the test scripts, standing in for AndroidKeyStore. */
private class FakeKeyStoreSpi : KeyStoreSpi() {
    var alias: SecretKey? = null
    var hasAlias = false
    var onContains: (() -> Unit)? = null
    var onGetKey: (() -> Key?)? = null
    var onLoad: (() -> Unit)? = null
    var deleted = 0

    override fun engineContainsAlias(a: String?): Boolean { onContains?.invoke(); return hasAlias && a == KeystoreKey.ALIAS }
    override fun engineGetKey(a: String?, password: CharArray?): Key? = onGetKey?.invoke() ?: alias
    override fun engineDeleteEntry(a: String?) { hasAlias = false; alias = null; deleted++ }
    override fun engineLoad(stream: InputStream?, password: CharArray?) { onLoad?.invoke() }
    override fun engineLoad(param: KeyStore.LoadStoreParameter?) { onLoad?.invoke() }
    override fun engineGetCertificateChain(a: String?): Array<Certificate>? = null
    override fun engineGetCertificate(a: String?): Certificate? = null
    override fun engineGetCreationDate(a: String?): Date? = null
    override fun engineSetKeyEntry(a: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) {}
    override fun engineSetKeyEntry(a: String?, key: ByteArray?, chain: Array<out Certificate>?) {}
    override fun engineSetCertificateEntry(a: String?, cert: Certificate?) {}
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(if (hasAlias) listOf(KeystoreKey.ALIAS) else emptyList())
    override fun engineSize(): Int = if (hasAlias) 1 else 0
    override fun engineIsKeyEntry(a: String?): Boolean = hasAlias
    override fun engineIsCertificateEntry(a: String?): Boolean = false
    override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    override fun engineStore(stream: OutputStream?, password: CharArray?) {}
}

private fun softKey(): SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

/** The `KeystoreKey` half of the no-wipe contract: how Keystore failures are classified, and the `createKey` guard. */
class KeystoreKeyTest {
    private val spi = FakeKeyStoreSpi()
    private val provider: Provider = javax.crypto.Cipher.getInstance("AES").provider
    private var generated = mutableListOf<Boolean>()

    private fun key(generate: (Boolean) -> SecretKey = { sb ->
        generated += sb; spi.hasAlias = true; spi.alias = softKey(); spi.alias!!
    }) = KeystoreKey(
        openKeyStore = { object : KeyStore(spi, provider, "AndroidKeyStore") {}.apply { load(null) } },
        generateKey = generate,
    )

    private fun present() { spi.hasAlias = true; spi.alias = softKey() }

    @Test
    fun cleanAbsentAliasIsLossOnDecryptAndFalseOnHasKey() {
        val k = key()
        assertFalse(k.hasKey())
        assertFailsWith<SecretsLost> { k.decryptCipher(ByteArray(12)) }
        assertEquals(0, spi.deleted)
    }

    @Test
    fun cleanAbsentAliasIsUnavailableNotLossOnEncrypt() {
        assertFailsWith<SecretStoreUnavailable> { key().encryptCipher() }
    }

    @Test
    fun presentAliasWithNoKeyIsUnavailableNeverLoss() {
        present()
        spi.onGetKey = { null }
        spi.alias = null
        val k = key()
        assertTrue(k.hasKey())
        assertFailsWith<SecretStoreUnavailable> { k.decryptCipher(ByteArray(12)) }
        assertFailsWith<SecretStoreUnavailable> { k.encryptCipher() }
    }

    @Test
    fun unrecoverableKeyIsUnavailable() {
        present()
        spi.onGetKey = { throw UnrecoverableKeyException("x") }
        assertFailsWith<SecretStoreUnavailable> { key().decryptCipher(ByteArray(12)) }
    }

    @Test
    fun everyOtherKeystoreErrorIsUnavailable() {
        present()
        val faults = listOf<() -> Unit>(
            { throw KeyStoreException("daemon") },
            { throw ProviderException("binder") },
            { throw IOException("load") },
        )
        for (f in faults) {
            spi.onContains = f
            val k = key()
            assertFailsWith<SecretStoreUnavailable> { k.hasKey() }
            assertFailsWith<SecretStoreUnavailable> { k.decryptCipher(ByteArray(12)) }
            assertFailsWith<SecretStoreUnavailable> { k.encryptCipher() }
            assertFailsWith<SecretStoreUnavailable> { k.createKey() }
            assertFailsWith<SecretStoreUnavailable> { k.deleteKey() }
        }
        spi.onContains = null
        spi.onLoad = { throw IOException("cannot open keystore") }
        assertFailsWith<SecretStoreUnavailable> { key().hasKey() }
        assertEquals(0, spi.deleted)
        assertTrue(generated.isEmpty())
    }

    @Test
    fun aKeyTheCipherRejectsIsUnavailable() {
        present()
        spi.alias = SecretKeySpec(ByteArray(5), "AES") // not a valid AES size: Cipher.init throws InvalidKeyException
        assertFailsWith<SecretStoreUnavailable> { key().encryptCipher() }
    }

    @Test
    fun existingKeyYieldsWorkingCiphers() {
        present()
        val k = key()
        val enc = k.encryptCipher()
        val ct = enc.doFinal(byteArrayOf(1, 2, 3))
        assertContentEquals3(byteArrayOf(1, 2, 3), k.decryptCipher(enc.iv).doFinal(ct))
    }

    private fun assertContentEquals3(a: ByteArray, b: ByteArray) = assertEquals(a.toList(), b.toList())

    @Test
    fun createKeyNeverReplacesAnExistingAlias() {
        present()
        val before = spi.alias
        key().createKey()
        assertTrue(generated.isEmpty(), "generator must not run when the alias exists")
        assertTrue(spi.alias === before)
        assertEquals(0, spi.deleted)
    }

    @Test
    fun createKeyMakesOneKeyWhenAbsentAndIsIdempotent() {
        val k = key()
        k.createKey()
        k.createKey()
        assertEquals(listOf(true), generated) // StrongBox first, once
        assertNotNull(spi.alias)
    }

    @Test
    fun createKeyFallsBackToTeeAndDropsAPartialAlias() {
        val k = key { sb ->
            generated += sb
            if (sb) { spi.hasAlias = true; throw ProviderException("StrongBox busy") }
            spi.alias = softKey(); spi.hasAlias = true; spi.alias!!
        }
        k.createKey()
        assertEquals(listOf(true, false), generated)
        assertEquals(1, spi.deleted)
        assertTrue(spi.hasAlias)
        assertEquals("tee", k.backing)
    }

    @Test
    fun deleteKeyRemovesTheAliasOnlyWhenPresent() {
        val k = key()
        k.deleteKey()
        assertEquals(0, spi.deleted)
        present()
        k.deleteKey()
        assertEquals(1, spi.deleted)
    }
}
