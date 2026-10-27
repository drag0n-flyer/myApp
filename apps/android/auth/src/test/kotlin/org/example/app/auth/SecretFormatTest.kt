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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import javax.crypto.AEADBadTagException
import java.io.IOException
import kotlin.test.assertTrue
import org.example.app.contracts.SecretStoreUnavailable
import org.example.app.contracts.SecretsLost

/**
 * A software AES key standing in for the Keystore. `lose()` models a clean "key is gone" answer; `fail` makes
 * every call throw a transient error without touching the key.
 */
private class FakeVault : WrappingKeyVault {
    private var key: SecretKey? = null
    @Volatile var fail: Boolean = false
    @Volatile var deleted = 0
    @Volatile var created = 0
    override val backing: String = "tee"

    private fun maybeFail() { if (fail) throw SecretStoreUnavailable("transient", null) }

    @Volatile var encryptOverride: (() -> Cipher)? = null
    @Volatile var decryptOverride: (() -> Cipher)? = null

    override fun encryptCipher(): Cipher {
        maybeFail()
        encryptOverride?.let { return it() }
        val k = key ?: throw SecretStoreUnavailable("no key", null)
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
    }

    override fun decryptCipher(nonce: ByteArray): Cipher {
        maybeFail()
        decryptOverride?.let { return it() }
        val k = key ?: throw SecretsLost("no key")
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce)) }
    }

    override fun hasKey(): Boolean { maybeFail(); return key != null }

    @Synchronized
    override fun createKey() {
        maybeFail()
        if (key == null) { Thread.sleep(2); key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(); created++ }
    }

    override fun deleteKey() { maybeFail(); key = null; deleted++ }
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
        assertEquals(1 + 12 + value.size + 16, raw.size)
        assertEquals(1, raw[0].toInt(), "format version byte")
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
        assertFailsWith<AEADBadTagException> { store(vault, dir).load("db.key") }
        assertTrue(File(dir, "db.key").exists())
    }

    @Test
    fun shortFileFailsToOpenAndStays() {
        val dir = File(tmp(), "secrets")
        val vault = FakeVault()
        store(vault, dir).save("session", byteArrayOf(1))
        File(dir, "session").writeBytes(ByteArray(10) { if (it == 0) 1 else 0 })
        assertFailsWith<IOException> { store(vault, dir).load("session") }
        assertTrue(File(dir, "session").exists())
    }

    @Test
    fun tagCutOffFailsToOpenAndStays() {
        val dir = File(tmp(), "secrets")
        val vault = FakeVault()
        store(vault, dir).save("session", ByteArray(40))
        val f = File(dir, "session")
        f.writeBytes(f.readBytes().copyOf(f.length().toInt() - 5))
        assertFailsWith<AEADBadTagException> { store(vault, dir).load("session") }
        assertTrue(f.exists())
    }

    @Test
    fun unknownVersionByteIsRejected() {
        val dir = File(tmp(), "secrets")
        val vault = FakeVault()
        store(vault, dir).save("session", byteArrayOf(1))
        val f = File(dir, "session")
        f.writeBytes(f.readBytes().also { it[0] = 9 })
        assertFailsWith<IOException> { store(vault, dir).load("session") }
    }

    @Test
    fun sameValueSealsWithDifferentNonces() {
        val dir = File(tmp(), "secrets")
        val s = store(dir = dir)
        s.save("a", byteArrayOf(1)); s.save("b", byteArrayOf(1))
        val na = File(dir, "a").readBytes().copyOfRange(1, 13)
        val nb = File(dir, "b").readBytes().copyOfRange(1, 13)
        assertNotEquals(na.toList(), nb.toList())
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

    private fun files(dir: File) = dir.listFiles()!!.map { it.name }.sorted()

    @Test
    fun transientErrorOnLoadKeepsEverythingAndThrows() {
        val dir = File(tmp(), "secrets")
        val vault = FakeVault()
        store(vault, dir).apply { save("session", byteArrayOf(1)); save("db.key", byteArrayOf(2)) }
        val before = files(dir)
        val fresh = store(vault, dir)
        vault.fail = true
        assertFailsWith<SecretStoreUnavailable> { fresh.load("session") }
        assertEquals(before, files(dir))
        assertEquals(0, vault.deleted)
        vault.fail = false
        assertContentEquals(byteArrayOf(1), fresh.load("session")) // retry later works
        assertContentEquals(byteArrayOf(2), fresh.load("db.key"))
    }

    @Test
    fun transientErrorOnSaveKeepsEverythingAndNeverMakesAKey() {
        val dir = File(tmp(), "secrets")
        val vault = FakeVault()
        store(vault, dir).save("session", byteArrayOf(1))
        val before = files(dir)
        val fresh = store(vault, dir)
        vault.fail = true
        assertFailsWith<SecretStoreUnavailable> { fresh.save("db.key", byteArrayOf(2)) }
        assertEquals(before, files(dir))
        assertEquals(1, vault.created)
        assertEquals(0, vault.deleted)
        vault.fail = false
        assertContentEquals(byteArrayOf(1), fresh.load("session"))
    }

    @Test
    fun lostKeyThrowsSecretsLostOnLoadAndWipesNothing() {
        val dir = File(tmp(), "secrets")
        val vault = FakeVault()
        store(vault, dir).apply { save("session", byteArrayOf(1)); save("db.key", byteArrayOf(2)) }
        val before = files(dir)
        vault.lose()
        val fresh = store(vault, dir)
        assertFailsWith<SecretsLost> { fresh.load("session") }
        assertFailsWith<SecretsLost> { fresh.load("db.key") }
        assertEquals(before, files(dir))
        assertEquals(0, vault.deleted)
    }

    @Test
    fun saveAfterKeyLossThrowsSecretsLostKeepsFilesAndMakesNoKey() {
        val dir = File(tmp(), "secrets")
        val vault = FakeVault()
        store(vault, dir).apply { save("session", byteArrayOf(1)); save("db.key", byteArrayOf(2)) }
        val before = files(dir)
        vault.lose()
        assertFailsWith<SecretsLost> { store(vault, dir).save("session", byteArrayOf(9)) }
        assertEquals(before, files(dir))
        assertEquals(1, vault.created)
        assertEquals(0, vault.deleted)
    }

    @Test
    fun explicitWipeAfterLossStartsAfresh() {
        val dir = File(tmp(), "secrets")
        val vault = FakeVault()
        store(vault, dir).save("session", byteArrayOf(1))
        vault.lose()
        val fresh = store(vault, dir)
        fresh.wipeAll()
        fresh.save("session", byteArrayOf(9))
        assertContentEquals(byteArrayOf(9), store(vault, dir).load("session"))
    }

    @Test
    fun neverSavedIsNull() {
        val dir = File(tmp(), "secrets")
        val vault = FakeVault()
        val s = store(vault, dir)
        assertNull(s.load("session"))
        s.save("db.key", byteArrayOf(1))
        assertNull(s.load("session"))
    }

    @Test
    fun twoCallersShareOneStoreAndMakeOneKey() {
        val dir = File(tmp(), "secrets")
        val vault = FakeVault()
        val made = java.util.concurrent.atomic.AtomicInteger()
        val factory = { made.incrementAndGet(); VaultSecretStore(vault, dir) {} }
        val stores = java.util.concurrent.ConcurrentLinkedQueue<Any>()
        val threads = (1..8).map { i ->
            Thread {
                val s = StoreRegistry.obtain(dir, factory)
                s.save("n$i", byteArrayOf(i.toByte()))
                stores += s
            }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(1, made.get())
        assertEquals(1, stores.map { System.identityHashCode(it) }.toSet().size)
        assertEquals(1, vault.created)
        for (i in 1..8) assertContentEquals(byteArrayOf(i.toByte()), StoreRegistry.obtain(dir, factory).load("n$i"))
    }

    @Test
    fun tempFileNameCannotCollideWithASecretName() {
        val dir = File(tmp(), "secrets")
        val s = store(dir = dir)
        s.save("x", byteArrayOf(1))
        s.save("x.tmp", byteArrayOf(2))
        assertContentEquals(byteArrayOf(1), s.load("x"))
        assertContentEquals(byteArrayOf(2), s.load("x.tmp"))
        assertFailsWith<IllegalArgumentException> { s.save("~tmp~x", byteArrayOf(3)) }
        assertEquals(listOf("x", "x.tmp"), files(dir))
    }

    @Test
    fun strongBoxFailureOfAnyProviderExceptionFallsBackToTee() {
        val tried = mutableListOf<Boolean>()
        var cleaned = 0
        val level = generateWithFallback(
            generate = { sb -> tried += sb; if (sb) throw java.security.ProviderException("hw busy") else "key" },
            cleanup = { cleaned++ },
        )
        assertEquals(listOf(true, false), tried)
        assertEquals("tee", level.second)
        assertEquals(1, cleaned)
    }

    @Test
    fun strongBoxSuccessNeedsNoFallback() {
        val level = generateWithFallback(generate = { "key" }, cleanup = { error("no cleanup") })
        assertEquals("strongbox", level.second)
    }

    @Test
    fun teeFailureIsNotSwallowed() {
        assertFailsWith<java.security.ProviderException> {
            generateWithFallback<String>(generate = { throw java.security.ProviderException("x") }, cleanup = {})
        }
    }

    @Test
    fun strongBoxInvalidAlgorithmParameterFallsBackToTee() {
        val tried = mutableListOf<Boolean>()
        var cleaned = 0
        val level = generateWithFallback(
            generate = { sb -> tried += sb; if (sb) throw java.security.InvalidAlgorithmParameterException("no strongbox") else "key" },
            cleanup = { cleaned++ },
        )
        assertEquals(listOf(true, false), tried)
        assertEquals("tee", level.second)
        assertEquals(1, cleaned)
    }

    @Test
    fun syncDirectorySucceedsOnARealDirectory() {
        assertTrue(syncDirectory(tmp()))
    }

    @Test
    fun syncDirectoryIsBestEffortOnAMissingDirectory() {
        assertFalse(syncDirectory(File(tmp(), "nope")))
    }

    /** A cipher that initialises and accepts AAD, then fails at `doFinal` like a keystore daemon would. */
    private fun cipherFailingAtDoFinal(fault: Throwable, iv: ByteArray = ByteArray(12)): Cipher {
        val spi = object : javax.crypto.CipherSpi() {
            override fun engineSetMode(mode: String?) {}
            override fun engineSetPadding(padding: String?) {}
            override fun engineGetBlockSize() = 16
            override fun engineGetOutputSize(inputLen: Int) = inputLen + 16
            override fun engineGetIV() = iv
            override fun engineUpdateAAD(src: ByteArray?, offset: Int, len: Int) {}
            override fun engineUpdateAAD(src: java.nio.ByteBuffer?) {}
            override fun engineGetParameters(): java.security.AlgorithmParameters? = null
            override fun engineInit(opmode: Int, key: java.security.Key?, random: java.security.SecureRandom?) {}
            override fun engineInit(opmode: Int, key: java.security.Key?, params: java.security.spec.AlgorithmParameterSpec?, random: java.security.SecureRandom?) {}
            override fun engineInit(opmode: Int, key: java.security.Key?, params: java.security.AlgorithmParameters?, random: java.security.SecureRandom?) {}
            override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray? = null
            override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int) = 0
            override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray = throw fault
            override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int = throw fault
        }
        val cipher = object : Cipher(spi, Cipher.getInstance("AES").provider, "AES/GCM/NoPadding") {}
        cipher.init(Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(ByteArray(16), "AES"))
        return cipher
    }

    private fun faults() = listOf<Throwable>(
        javax.crypto.IllegalBlockSizeException("pruned"),
        java.security.ProviderException("INVALID_OPERATION_HANDLE"),
        javax.crypto.BadPaddingException("other"),
    )

    @Test
    fun keystoreSideDoFinalFailureOnLoadIsUnavailableAndKeepsEverything() {
        for (fault in faults()) {
            val dir = File(tmp(), "secrets")
            val vault = FakeVault()
            store(vault, dir).save("session", byteArrayOf(1))
            val before = files(dir)
            vault.decryptOverride = { cipherFailingAtDoFinal(fault) }
            val ex = assertFailsWith<SecretStoreUnavailable>(fault.toString()) { store(vault, dir).load("session") }
            assertTrue(ex.cause === fault)
            assertEquals(before, files(dir))
            assertEquals(0, vault.deleted)
            vault.decryptOverride = null
            assertContentEquals(byteArrayOf(1), store(vault, dir).load("session"))
        }
    }

    @Test
    fun keystoreSideDoFinalFailureOnSaveIsUnavailableAndWritesNothing() {
        for (fault in faults()) {
            val dir = File(tmp(), "secrets")
            val vault = FakeVault()
            val s = store(vault, dir)
            s.save("session", byteArrayOf(1))
            val before = files(dir)
            vault.encryptOverride = { cipherFailingAtDoFinal(fault) }
            val ex = assertFailsWith<SecretStoreUnavailable>(fault.toString()) { s.save("session", byteArrayOf(9)) }
            assertTrue(ex.cause === fault)
            assertEquals(before, files(dir))
            assertEquals(0, vault.deleted)
            vault.encryptOverride = null
            assertContentEquals(byteArrayOf(1), s.load("session")) // cache not poisoned
            assertContentEquals(byteArrayOf(1), store(vault, dir).load("session"))
        }
    }

    @Test
    fun badTagStaysACorruptItemNotUnavailable() {
        val dir = File(tmp(), "secrets")
        val vault = FakeVault()
        store(vault, dir).save("session", byteArrayOf(1))
        vault.decryptOverride = { cipherFailingAtDoFinal(AEADBadTagException("bad tag")) }
        val ex = assertFailsWith<AEADBadTagException> { store(vault, dir).load("session") }
        assertFalse((ex as Throwable) is SecretStoreUnavailable)
        assertTrue(File(dir, "session").exists())
    }

    @Test
    fun transientFailureAtAnyStepOfSaveChangesNothing() {
        val steps = listOf<(FakeVault) -> Unit>(
            { it.fail = true },
            { it.encryptOverride = { throw SecretStoreUnavailable("busy", null) } },
            { it.encryptOverride = { cipherFailingAtDoFinal(java.security.ProviderException("x")) } },
        )
        for (step in steps) {
            val dir = File(tmp(), "secrets")
            val vault = FakeVault()
            val s = store(vault, dir)
            s.save("session", byteArrayOf(1))
            val before = files(dir)
            val createdBefore = vault.created
            step(vault)
            assertFailsWith<SecretStoreUnavailable> { s.save("db.key", byteArrayOf(2)) }
            assertFailsWith<SecretStoreUnavailable> { s.save("session", byteArrayOf(3)) }
            assertEquals(before, files(dir), "no new file, no temp file")
            assertEquals(createdBefore, vault.created)
            assertEquals(0, vault.deleted)
            vault.fail = false; vault.encryptOverride = null
            assertContentEquals(byteArrayOf(1), s.load("session"))
            assertNull(s.load("db.key"))
            assertContentEquals(byteArrayOf(1), store(vault, dir).load("session"))
        }
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
