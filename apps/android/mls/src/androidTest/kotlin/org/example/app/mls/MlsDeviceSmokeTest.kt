package org.example.app.mls

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.example.app.contracts.HpkeException
import org.example.app.contracts.HpkeInfo
import org.example.app.contracts.Incoming
import org.example.app.contracts.MemorySecretStore
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Loads the real libmlsffi.so on the device ABI and runs one engine round trip and one HPKE round trip. */
@RunWith(AndroidJUnit4::class)
class MlsDeviceSmokeTest {
    private val dirs = mutableListOf<File>()

    private fun dir(): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return java.nio.file.Files.createTempDirectory(cache.toPath(), "mls-smoke").toFile().also { dirs += it }
    }

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun loadsOnDeviceAbi() = runBlocking {
        val a = LiveMlsEngine("a1/d1", dir(), MemorySecretStore())
        val b = LiveMlsEngine("b1/d1", dir(), MemorySecretStore())
        val group = a.createGroup()
        val bundle = a.add(b.makeKeyPackages(1), group)
        a.commitAccepted(group)
        assertIs<Incoming.Joined>(b.process(assertNotNull(bundle.welcome)))
        val sealed = a.encrypt("hello".toByteArray(), group)
        assertEquals(1L, sealed.epoch)
        val incoming = b.process(sealed.ciphertext)
        assertIs<Incoming.Application>(incoming)
        assertContentEquals("hello".toByteArray(), incoming.plaintext)

        val hpke = RustHpke()
        val keys = hpke.generateKeyPair()
        val box = hpke.seal(keys.publicKey, HpkeInfo.LINK, "link".toByteArray())
        assertContentEquals("link".toByteArray(), hpke.open(keys, HpkeInfo.LINK, box))
        assertFailsWith<HpkeException> { hpke.open(keys, HpkeInfo.ESCROW, box) }
        assertFailsWith<HpkeException> { hpke.seal(ByteArray(32), HpkeInfo.LINK, byteArrayOf(1)) }
        Unit
    }
}
