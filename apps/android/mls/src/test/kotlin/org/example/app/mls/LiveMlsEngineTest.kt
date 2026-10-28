package org.example.app.mls

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.app.contracts.GroupId
import org.example.app.contracts.Incoming
import org.example.app.contracts.MemorySecretStore
import org.example.app.contracts.MlsException
import org.example.app.contracts.SecretNames
import org.example.app.contracts.SecretStore

class LiveMlsEngineTest {
    private val dirs = mutableListOf<File>()

    private fun dir(): File = java.nio.file.Files.createTempDirectory("mls-test").toFile().also { dirs += it }

    private fun engine(identity: String, dir: File = dir(), secrets: SecretStore = MemorySecretStore()) =
        LiveMlsEngine(identity, dir, secrets)

    @kotlin.test.AfterTest
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    /** A creates a group and adds B; returns the group after both are at the same epoch. */
    private suspend fun pair(a: LiveMlsEngine, b: LiveMlsEngine): GroupId {
        val group = a.createGroup()
        val bundle = a.add(b.makeKeyPackages(1), group)
        a.commitAccepted(group)
        val joined = b.process(assertNotNull(bundle.welcome))
        assertIs<Incoming.Joined>(joined)
        return group
    }

    @Test
    fun createAddWelcomeJoin() = runBlocking {
        val a = engine("a1/d1")
        val b = engine("b1/d1")
        val group = a.createGroup()
        assertEquals(0L, a.epoch(group))
        val bundle = a.add(b.makeKeyPackages(1), group)
        assertEquals(1L, bundle.newEpoch)
        a.commitAccepted(group)
        assertEquals(1L, a.epoch(group))
        val joined = b.process(assertNotNull(bundle.welcome))
        assertIs<Incoming.Joined>(joined)
        assertEquals(group, joined.group)
        assertEquals(1L, joined.epoch)
        assertEquals(listOf("a1/d1", "b1/d1"), a.members(group).sorted())
        assertEquals(listOf("a1/d1", "b1/d1"), b.members(group).sorted())
    }

    @Test
    fun processApplicationRefusesCommitAndWelcomeAndChangesNothing() = runBlocking {
        val a = engine("a1/d1")
        val b = engine("b1/d1")
        val c = engine("c1/d1")
        val group = a.createGroup()
        val first = a.add(b.makeKeyPackages(1), group)
        a.commitAccepted(group)
        assertFailsWith<MlsException.NotApplication> { b.processApplication(group, assertNotNull(first.welcome)) }
        assertFailsWith<MlsException.UnknownGroup> { b.epoch(group) }
        assertIs<Incoming.Joined>(b.process(assertNotNull(first.welcome)))
        val commit = a.add(c.makeKeyPackages(1), group)
        a.commitAccepted(group)
        assertFailsWith<MlsException.NotApplication> { b.processApplication(group, commit.commit) }
        assertEquals(1L, b.epoch(group))
        assertIs<Incoming.Commit>(b.process(commit.commit))
        assertEquals(2L, b.epoch(group))
        val app = b.processApplication(group, a.encrypt("call".toByteArray(), group).ciphertext)
        assertContentEquals("call".toByteArray(), app.plaintext)
        assertEquals("a1/d1", app.sender)
        val ct = a.encrypt("again".toByteArray(), group).ciphertext
        assertEquals(group, b.groupOf(ct))
        assertFailsWith<MlsException.Malformed> { b.processApplication(GroupId(byteArrayOf(9, 9)), ct) }
        Unit
    }

    @Test
    fun encryptProcessRoundTrip() = runBlocking {
        val a = engine("a1/d1")
        val b = engine("b1/d1")
        val group = pair(a, b)
        val sealed = a.encrypt("hello".toByteArray(), group)
        assertEquals(1L, sealed.epoch)
        val incoming = b.process(sealed.ciphertext)
        assertIs<Incoming.Application>(incoming)
        assertEquals("a1/d1", incoming.sender)
        assertContentEquals("hello".toByteArray(), incoming.plaintext)
        assertEquals(group, incoming.group)
        val secret = a.exportSecret(group, "test", 16)
        assertEquals(16, secret.size)
        assertContentEquals(secret, b.exportSecret(group, "test", 16))
    }

    @Test
    fun staleEpochCommitRejectedThenRetry() = runBlocking {
        val a = engine("a1/d1")
        val b = engine("b1/d1")
        val c = engine("c1/d1")
        val d = engine("d1/d1")
        val group = pair(a, b)
        // Both A and B build a commit at epoch 1; A's wins the race.
        b.add(c.makeKeyPackages(1), group)
        val aWin = a.add(d.makeKeyPackages(1), group)
        a.commitAccepted(group)
        b.commitRejected(group)
        assertIs<Incoming.Commit>(b.process(aWin.commit))
        assertEquals(2L, b.epoch(group))
        // B retries on the new epoch.
        val retry = b.add(c.makeKeyPackages(1), group)
        assertEquals(3L, retry.newEpoch)
    }

    @Test
    fun pendingCommitBlocksSecond() = runBlocking {
        val a = engine("a1/d1")
        val b = engine("b1/d1")
        val c = engine("c1/d1")
        val group = pair(a, b)
        a.add(c.makeKeyPackages(1), group)
        assertFailsWith<MlsException.PendingCommit> { a.add(c.makeKeyPackages(1), group) }
        Unit
    }

    @Test
    fun duplicateIsTyped() = runBlocking {
        val a = engine("a1/d1")
        val b = engine("b1/d1")
        val group = pair(a, b)
        val sealed = a.encrypt("once".toByteArray(), group)
        b.process(sealed.ciphertext)
        assertFailsWith<MlsException.Duplicate> { b.process(sealed.ciphertext) }
        Unit
    }

    @Test
    fun epochGapIsTyped() = runBlocking {
        val a = engine("a1/d1")
        val b = engine("b1/d1")
        val c = engine("c1/d1")
        val d = engine("d1/d1")
        val group = pair(a, b)
        a.add(c.makeKeyPackages(1), group)
        a.commitAccepted(group)
        val second = a.add(d.makeKeyPackages(1), group)
        a.commitAccepted(group)
        // B never saw the first commit.
        assertFailsWith<MlsException.EpochGap> { b.process(second.commit) }
        Unit
    }

    @Test
    fun restartKeepsGroups() = runBlocking {
        val aDir = dir()
        val secrets = MemorySecretStore()
        val a = engine("a1/d1", aDir, secrets)
        val b = engine("b1/d1")
        val group = pair(a, b)
        val restarted = engine("a1/d1", aDir, secrets)
        assertEquals(1L, restarted.epoch(group))
        val sealed = restarted.encrypt("after restart".toByteArray(), group)
        val incoming = b.process(sealed.ciphertext)
        assertIs<Incoming.Application>(incoming)
        assertContentEquals("after restart".toByteArray(), incoming.plaintext)
    }

    @Test
    fun signingKeyPersistsAcrossRestart() {
        val secrets = MemorySecretStore()
        val first = SigningKeys.loadOrCreate(secrets)
        val second = SigningKeys.loadOrCreate(secrets)
        assertContentEquals(first.publicKey, second.publicKey)
        assertContentEquals(first.secretKey, second.secretKey)
        val stored = String(assertNotNull(secrets.load(SecretNames.MLS_SIGNING)))
        assertTrue(stored.contains("\"pub\"") && stored.contains("\"sec\""))
        val other = SigningKeys.loadOrCreate(MemorySecretStore())
        assertTrue(!first.publicKey.contentEquals(other.publicKey))
    }

    @Test
    fun errorMappingIsOneToOne() {
        assertEquals(MlsException.Malformed, ErrorMapping.map(org.example.app.mls.ffi.FfiException.Malformed()))
        assertEquals(MlsException.PendingCommit, ErrorMapping.map(org.example.app.mls.ffi.FfiException.PendingCommit()))
        assertIs<MlsException.Storage>(ErrorMapping.map(org.example.app.mls.ffi.FfiException.Storage("x")))
        assertIs<MlsException.Internal>(ErrorMapping.map(IllegalStateException("panic")))
    }

    @Test
    fun memberKeysAreSortedPlaceholdersUntilM7() = runBlocking {
        val a = engine("b1/d1")
        val b = engine("a1/d1")
        val group = a.createGroup()
        val bundle = a.add(b.makeKeyPackages(1), group)
        a.commitAccepted(group)
        b.process(assertNotNull(bundle.welcome))
        assertTrue(!a.providesRealMemberKeys)
        val keys = a.memberKeys(group)
        assertEquals(listOf("a1/d1", "b1/d1"), keys.map { it.identity })
        assertEquals("fake-sig:a1/d1", String(keys[0].signatureKey))
    }

    @Test
    fun callerHoldingGateCanCallEngine() = runBlocking {
        val a = engine("a1/d1")
        withTimeout(10_000) { MlsGate.withLock { a.createGroup() } }
        Unit
    }

    @Test
    fun cancelledCallerStillAppliesStateChange() = runBlocking {
        val a = engine("a1/d1")
        val b = engine("b1/d1")
        val group = pair(a, b)
        val sealed = a.encrypt("x".toByteArray(), group)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { b.process(sealed.ciphertext) }
        job.cancel()
        job.join()
        // The call ran to completion although the caller was cancelled: the message is consumed.
        assertFailsWith<MlsException.Duplicate> { b.process(sealed.ciphertext) }
        Unit
    }

    @Test
    fun corruptStoredSigningKeyFailsClosed() {
        val secrets = MemorySecretStore()
        secrets.save(SecretNames.MLS_SIGNING, """{"pub":"","sec":""}""".toByteArray())
        assertFailsWith<MlsException.Storage> { SigningKeys.loadOrCreate(secrets) }
    }
}
