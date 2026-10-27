package org.example.app.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.KeyStore
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
    private val dir = File(context.cacheDir, "secrets-test")

    private fun newStore() = KeystoreSecretStore(context, dir)
    private fun aliasExists() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(KeystoreKey.ALIAS)

    @BeforeTest fun clean() { newStore().wipeAll() }
    @AfterTest fun tearDown() { newStore().wipeAll(); dir.deleteRecursively() }

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
        assertEquals(12 + value.size + 16, raw.size)
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(String(value, Charsets.ISO_8859_1)))
    }

    @Test
    fun swappedSecretFailsToOpen() {
        val s = newStore()
        s.save("session", byteArrayOf(1))
        s.save("db.key", byteArrayOf(2))
        File(dir, "session").copyTo(File(dir, "db.key"), overwrite = true)
        assertFailsWith<Exception> { newStore().load("db.key") }
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
    fun strongBoxFallsBackToTee() {
        // The emulator has no StrongBox, so the key must be made in the TEE (software-backed on the emulator).
        val key = KeystoreKey()
        key.encryptCipher() // makes the key
        assertEquals("tee", key.backing())
    }
}
