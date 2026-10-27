package org.example.app.auth

import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A software AES key standing in for the Keystore. `lose()` models the key being permanently invalidated. */
private class FakeVault : WrappingKeyVault {
    private var key: SecretKey? = null
    var deleted = 0
    var created = 0
    override val backing: String = "tee"

    override fun encryptCipher(): Cipher {
        val k = key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it; created++ }
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
    }

    override fun decryptCipher(nonce: ByteArray): Cipher {
        val k = key ?: throw KeyLostException("no key")
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce)) }
    }

    override fun hasKey() = key != null
    override fun deleteKey() { key = null; deleted++ }
    fun lose() { key = null }
}

class SecretFormatTest {
    private val dirs = mutableListOf<File>()
    private fun tmp(): File = Files.createTempDirectory("secrets").toFile().also { dirs += it }
    @AfterTest fun cleanup() { dirs.forEach { it.deleteRecursively() } }

    private fun store(vault: WrappingKeyVault = FakeVault(), dir: File = File(tmp(), "secrets")) =
        VaultSecretStore(vault, dir) {}

    @Test
    fun roundTrip() {
        val s = store()
        s.save("session", "hello".toByteArray())
        assertContentEquals("hello".toByteArray(), s.load("session"))
        assertNull(s.load("absent"))
    }

    @Test
    fun fileLayoutIsNonceCiphertextTagAndNotPlaintext() {
        val dir = File(tmp(), "secrets")
        val s = store(dir = dir)
        val value = "a-very-recognisable-secret".toByteArray()
        s.save("db.key", value)
        val raw = File(dir, "db.key").readBytes()
        assertEquals(12 + value.size + 16, raw.size)
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(String(value, Charsets.ISO_8859_1)))
    }

    @Test
    fun loadsFromDiskInAFreshInstance() {
        val dir = File(tmp(), "secrets")
        val vault = FakeVault()
        store(vault, dir).save("session", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), store(vault, dir).load("session"))
    }

    @Test
    fun swappedSecretFailsToOpen() {
        val dir = File(tmp(), "secrets")
        val vault = FakeVault()
        val s = store(vault, dir)
        s.save("session", byteArrayOf(1))
        s.save("db.key", byteArrayOf(2))
        File(dir, "session").copyTo(File(dir, "db.key"), overwrite = true)
        // A fresh instance, so the in-memory cache cannot hide the swap.
        assertFailsWith<Exception> { store(vault, dir).load("db.key") }
    }

    @Test
    fun truncatedFileFailsToOpen() {
        val dir = File(tmp(), "secrets")
        val vault = FakeVault()
        store(vault, dir).save("session", byteArrayOf(1))
        File(dir, "session").writeBytes(ByteArray(10))
        assertFailsWith<Exception> { store(vault, dir).load("session") }
    }

    @Test
    fun deleteRemovesFileAndCache() {
        val dir = File(tmp(), "secrets")
        val s = store(dir = dir)
        s.save("session", byteArrayOf(1))
        s.delete("session")
        assertNull(s.load("session"))
        assertFalse(File(dir, "session").exists())
    }

    @Test
    fun wipeAllDeletesKeyAndFiles() {
        val dir = File(tmp(), "secrets")
        val vault = FakeVault()
        val s = store(vault, dir)
        s.save("session", byteArrayOf(1))
        s.save("db.key", byteArrayOf(2))
        s.wipeAll()
        assertEquals(1, vault.deleted)
        assertEquals(0, dir.listFiles()?.size ?: 0)
        assertNull(s.load("session"))
    }

    @Test
    fun invalidatedKeyWipesEverythingAndReadsAsAbsent() {
        val dir = File(tmp(), "secrets")
        val vault = FakeVault()
        store(vault, dir).apply { save("session", byteArrayOf(1)); save("db.key", byteArrayOf(2)) }
        vault.lose() // the person removed the screen lock, or the key was otherwise invalidated
        val fresh = store(vault, dir)
        assertNull(fresh.load("session"))
        assertEquals(0, dir.listFiles()?.size ?: 0, "unreadable files are dropped, like iPhone drops a corrupt item")
        assertNull(fresh.load("db.key"))
    }

    @Test
    fun saveAfterKeyLossStartsAfresh() {
        val dir = File(tmp(), "secrets")
        val vault = FakeVault()
        store(vault, dir).apply { save("session", byteArrayOf(1)); save("db.key", byteArrayOf(2)) }
        vault.lose()
        val fresh = store(vault, dir)
        fresh.save("session", byteArrayOf(9))
        assertContentEquals(byteArrayOf(9), fresh.load("session"))
        assertNull(store(vault, dir).load("db.key"))
    }

    @Test
    fun unsafeNamesAreRejected() {
        val s = store()
        for (bad in listOf("", "../x", "a/b", ".", "..", "a\\b", "a\u0000b")) {
            assertFailsWith<IllegalArgumentException>(bad) { s.save(bad, byteArrayOf(1)) }
        }
    }

    @Test
    fun valuesAreCopiedNotAliased() {
        val s = store()
        val v = byteArrayOf(1, 2)
        s.save("x", v)
        v[0] = 9
        val got = s.load("x")!!
        got[1] = 9
        assertContentEquals(byteArrayOf(1, 2), s.load("x"))
    }

    @Test
    fun backingIsLoggedOnceWithoutOtherDetail() {
        val lines = mutableListOf<String>()
        val s = VaultSecretStore(FakeVault(), File(tmp(), "secrets")) { lines += it }
        s.save("a", byteArrayOf(1)); s.save("b", byteArrayOf(2)); s.load("a")
        assertEquals(listOf("keystore=tee"), lines)
        assertTrue(lines.none { it.contains("secret") })
    }
}
