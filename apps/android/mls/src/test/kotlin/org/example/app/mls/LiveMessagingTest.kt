package org.example.app.mls

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.example.app.contracts.GroupId
import org.example.app.contracts.MemorySecretStore
import org.example.app.contracts.ServerException
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.MlsLock
import org.example.app.messaging.sync.ConversationManager
import org.example.app.messaging.sync.InboxProcessor
import org.example.app.messaging.sync.IncomingHandler
import org.example.app.messaging.sync.LocalDevice
import org.example.app.messaging.sync.Membership
import org.example.app.messaging.testing.FakeDeliveryServer
import org.example.app.messaging.testing.InMemoryRepository

/** `:messaging`'s conversation manager and inbox processor over two real engines (host `libmlsffi`), with `MlsGate` as the lock. */
class LiveMessagingTest {
    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private val gate = object : MlsLock {
        override suspend fun <T> withLock(block: suspend () -> T): T = MlsGate.withLock(block)
    }

    private inner class Device(server: FakeDeliveryServer, account: String, device: String) {
        val me = LocalDevice(account, device)
        val mls = LiveMlsEngine(me.identity, java.nio.file.Files.createTempDirectory("mls-msg").toFile().also { dirs += it }, MemorySecretStore())
        val api = server.api(account, device)
        val repo = InMemoryRepository()
        val clock = Clock { 1_700_000_000_000 }
        val membership = Membership(me, mls, mls.providesRealMemberKeys)
        lateinit var processor: InboxProcessor
        val manager = ConversationManager(me, mls, api, repo, gate, clock, membership, syncOnce = { processor.sync() })

        init {
            processor = InboxProcessor(mls, api, repo, gate, clock, manager, IncomingHandler(me, clock), membership)
        }
    }

    @Test
    fun createJoinPinAndMessageOverLiveEngine() = runBlocking {
        val server = FakeDeliveryServer()
        val ana = Device(server, "acc-ana", "ana1")
        val bob = Device(server, "acc-bob", "bob1")
        for (d in listOf(ana, bob)) d.api.uploadKeyPackages(d.mls.makeKeyPackages(2))

        server.loseNextCommitResponses = 1 // the reply is lost: the echo merges the pending commit on the real engine
        val record = ana.manager.create("acc-bob")
        assertEquals(1, server.commitPosts.size)
        assertEquals(1L, record.epoch)
        assertEquals(1L, ana.mls.epoch(GroupId(record.groupId)))

        bob.processor.sync()
        val joined = assertNotNull(bob.repo.conversation(record.serverId))
        assertEquals("e2ee", joined.innerMode, "pinned from the creator's mode envelope")
        assertFalse(joined.modeMismatch)
        assertEquals(true, bob.repo.chat("acc-ana")?.isRequest)
        assertEquals(listOf("acc-ana/ana1", "acc-bob/bob1"), bob.repo.members(record.serverId).map { it.identity })

        // ana's own commit comes back as an echo and is skipped without touching the engine.
        ana.processor.sync()
        assertTrue(ana.repo.rawItems(10).isEmpty())
        assertEquals(1L, ana.mls.epoch(GroupId(record.groupId)))

        val text = ContentEnvelope.Text("m-1", 1_700_000_000_001, "hello")
        val encrypted = gate.withLock { ana.mls.encrypt(ContentCodec.encode(text), GroupId(record.groupId)) }
        ana.api.postMessage(record.serverId, encrypted.epoch, encrypted.ciphertext, null, text.id)
        bob.processor.sync()
        val message = assertNotNull(bob.repo.message("m-1"))
        assertEquals("acc-ana", message.senderAccount)
        assertEquals("ana1", message.senderDevice)
        assertFalse(message.undecryptable)
    }

    /** The add commit never reached the server: the retry posts the same bytes, the real engine merges them once. */
    @Test
    fun createRequestThatNeverArrivedIsResentOverLiveEngine() = runBlocking {
        val server = FakeDeliveryServer()
        val ana = Device(server, "acc-ana", "ana1")
        val bob = Device(server, "acc-bob", "bob1")
        for (d in listOf(ana, bob)) d.api.uploadKeyPackages(d.mls.makeKeyPackages(2))

        server.holdNextCommitRequests = 1
        assertFailsWith<ServerException> { ana.manager.create("acc-bob") }
        val record = ana.manager.preferredConversation("acc-bob")
        assertEquals(listOf(record.serverId), server.conversationIds())
        assertEquals(1L, ana.mls.epoch(GroupId(record.groupId)))
        assertTrue(server.releaseHeldCommits().single().isFailure, "the late original is refused")

        bob.processor.sync()
        assertEquals("e2ee", assertNotNull(bob.repo.conversation(record.serverId)).innerMode)
        ana.processor.sync()
        assertTrue(ana.repo.allMessages().none { it.undecryptable })
        assertEquals(1L, ana.mls.epoch(GroupId(record.groupId)))
    }
}
