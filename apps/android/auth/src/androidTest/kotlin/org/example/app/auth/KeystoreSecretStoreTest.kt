package org.example.app.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import android.content.pm.PackageManager
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs against the real Android Keystore, so it needs an emulator or device (CI task G3). */
class KeystoreSecretStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.noBackupFilesDir, "secrets")

    private fun newStore() = KeystoreSecretStore(context, dir)
    private fun aliasExists() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(KeystoreKey.ALIAS)

    @BeforeTest fun clean() { newStore().wipeAll() }
    @AfterTest fun tearDown() { newStore().wipeAll(); }

    @Test
    fun roundTrip() {
        newStore().save("session", "hello".toByteArray())
        assertContentEquals("hello".toByteArray(), newStore().load("session"))
        assertNull(newStore().load("absent"))
    }

    @Test
    fun wrappedSecretIsNotPlaintext() {
        val value = "a-very-recognisable-secret".toByteArray()
        newStore().save("db.key", value)
        val raw = File(dir, "db.key").readBytes()
        assertEquals(1 + 12 + value.size + 16, raw.size)
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(String(value, Charsets.ISO_8859_1)))
    }

    @Test
    fun swappedSecretFailsToOpen() {
        val s = newStore()
        s.save("session", byteArrayOf(1))
        s.save("db.key", byteArrayOf(2))
        File(dir, "session").copyTo(File(dir, "db.key"), overwrite = true)
        assertFailsWith<AEADBadTagException> { newStore().load("db.key") }
        assertTrue(aliasExists())
        assertTrue(File(dir, "db.key").exists())
    }

    @Test
    fun wipeAllDeletesAliasAndFiles() {
        val s = newStore()
        s.save("session", byteArrayOf(1))
        assertTrue(aliasExists())
        s.wipeAll()
        assertFalse(aliasExists())
        assertEquals(0, dir.listFiles()?.size ?: 0)
        assertNull(newStore().load("session"))
    }

    @Test
    fun keyIsNotExportable() {
        newStore().save("session", byteArrayOf(1))
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(KeystoreKey.ALIAS, null)
        assertNull(key!!.encoded)
    }

    @Test
    fun keyHasTheExpectedParameters() {
        newStore().save("session", byteArrayOf(1))
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(KeystoreKey.ALIAS, null) as SecretKey
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals("AES", key.algorithm)
        assertEquals(256, info.keySize)
        assertEquals(listOf(KeyProperties.BLOCK_MODE_GCM), info.blockModes.toList())
        assertEquals(listOf(KeyProperties.ENCRYPTION_PADDING_NONE), info.encryptionPaddings.toList())
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, info.purposes)
        assertFalse(info.isUserAuthenticationRequired)
    }

    @Test
    fun keyLivesInStrongBoxWhenPresentOtherwiseTee() {
        val key = KeystoreKey()
        key.createKey()
        val hasStrongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        // StrongBox is tried first; if the phone has it the key may land there, and if its attempt fails the TEE is used.
        // Without the feature the key can only be in the TEE.
        val backing = key.backing()
        if (hasStrongBox) assertTrue(backing == KeystoreKey.STRONGBOX || backing == KeystoreKey.TEE, backing)
        else assertEquals(KeystoreKey.TEE, backing)
    }

    @Test
    fun createKeyNeverReplacesAnExistingKey() {
        newStore().save("session", byteArrayOf(7))
        KeystoreKey().createKey()
        assertContentEquals(byteArrayOf(7), newStore().load("session"))
    }
}
