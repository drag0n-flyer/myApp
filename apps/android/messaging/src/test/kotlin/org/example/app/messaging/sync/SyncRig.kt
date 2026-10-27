package org.example.app.messaging.sync

import kotlin.test.assertEquals
import org.example.app.contracts.FakeMlsEngine
import org.example.app.contracts.GroupId
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.testing.FakeDeliveryServer
import org.example.app.messaging.testing.InMemoryRepository
import org.example.app.messaging.testing.SpyMlsEngine
import org.example.app.messaging.testing.TestMlsLock

const val NOW = 1_700_000_000_000L

/** One device with its own engine, database and lock, wired like the app wires `:messaging`. */
class TestDevice(val server: FakeDeliveryServer, account: String, device: String) {
    val me = LocalDevice(account, device)
    val lock = TestMlsLock()
    val mls = SpyMlsEngine(FakeMlsEngine(me.identity)) { lock.isHeld }
    val api = server.api(account, device)
    val repo = InMemoryRepository()
    val clock = Clock { NOW }
    val membership = Membership(me, mls, realMemberKeys = false)
    val logs = mutableListOf<String>()
    val handler = IncomingHandler(me, clock, log = { logs += it })
    var syncs = 0
    var manager: ConversationManager = makeManager()
        private set
    var processor = InboxProcessor(mls, api, repo, lock, clock, manager, handler, membership)
        private set

    private fun makeManager() = ConversationManager(me, mls, api, repo, lock, clock, membership, syncOnce = { syncs += 1; processor.sync() })

    /** A process restart: new manager and processor (no in-memory state), the same database, MLS storage and server. */
    fun restart() {
        manager = makeManager()
        processor = InboxProcessor(mls, api, repo, lock, clock, manager, handler, membership)
    }

    /** Sends a content envelope in [conversation] the way the outbox does: encrypt under the lock, post outside it. */
    suspend fun send(conversation: ConversationRecord, envelope: ContentEnvelope) {
        val encrypted = lock.withLock { mls.encrypt(ContentCodec.encode(envelope), GroupId(conversation.groupId)) }
        api.postMessage(conversation.serverId, encrypted.epoch, encrypted.ciphertext, null, envelope.id)
    }
}

/** Thrown by a test hook to model the process being killed: no `catch (e: Exception)` stops it. */
class SimulatedKill : Error("killed")

/** ana1, ana2, bob1 and bob2, each with KeyPackages on the server. Every network call is checked for a held lock. */
class Rig(mode: String = "e2ee") {
    val server = FakeDeliveryServer(mode)
    val ana1 = TestDevice(server, "acc-ana", "ana1")
    val ana2 = TestDevice(server, "acc-ana", "ana2")
    val bob1 = TestDevice(server, "acc-bob", "bob1")
    val bob2 = TestDevice(server, "acc-bob", "bob2")
    val devices = listOf(ana1, ana2, bob1, bob2)
    val lockedNetworkCalls = mutableListOf<String>()

    suspend fun start(): Rig {
        server.onCall = { identity, call ->
            if (devices.firstOrNull { it.me.identity == identity }?.lock?.isHeld == true) lockedNetworkCalls += "$identity $call"
        }
        for (d in devices) d.api.uploadKeyPackages(d.mls.makeKeyPackages(3))
        return this
    }

    /** Never a network call under the lock, never an engine change outside it. */
    fun assertLockDiscipline() {
        assertEquals(emptyList(), lockedNetworkCalls, "network calls under the MLS lock")
        devices.forEach { assertEquals(emptyList(), it.mls.unlockedCalls, "engine changes outside the lock on ${it.me.identity}") }
    }
}
