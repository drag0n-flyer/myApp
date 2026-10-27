package org.example.app.messaging.sync

import kotlin.test.assertEquals
import org.example.app.contracts.FakeMlsEngine
import org.example.app.contracts.GroupId
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.crypto.Escrow
import org.example.app.messaging.crypto.EscrowKeySource
import org.example.app.messaging.send.Outbox
import org.example.app.messaging.send.Receipts
import org.example.app.messaging.testing.SoftwareHpke
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.testing.FakeDeliveryServer
import org.example.app.messaging.testing.InMemoryRepository
import org.example.app.messaging.testing.SpyMlsEngine
import org.example.app.messaging.testing.TestMlsLock

const val NOW = 1_700_000_000_000L

/** One device with its own engine, database and lock, wired like the app wires `:messaging`. */
class TestDevice(val server: FakeDeliveryServer, account: String, device: String, escrowKey: ByteArray? = null) {
    val me = LocalDevice(account, device)
    val lock = TestMlsLock()
    val mls = SpyMlsEngine(FakeMlsEngine(me.identity)) { lock.isHeld }
    val api = server.api(account, device)
    val repo = InMemoryRepository()
    val clock = Clock { NOW }
    val membership = Membership(me, mls, realMemberKeys = false, clock = clock)
    val logs = mutableListOf<String>()
    val handler = IncomingHandler(me, clock, log = { logs += it })
    val hpke = SoftwareHpke()
    val escrow = escrowKey?.let { key -> Escrow.sealer(me.accountId, hpke, EscrowKeySource { key }) }
    val ownDeviceNotices = mutableListOf<Pair<String, String>>()
    val notifier = DeviceNotifier { id, name -> ownDeviceNotices += id to name }
    var syncs = 0
    var manager: ConversationManager = makeManager()
        private set
    var processor = makeProcessor()
        private set
    var outbox: Outbox = makeOutbox()
        private set
    var receipts: Receipts = Receipts(outbox)
        private set
    var deviceSync: DeviceSync = makeDeviceSync()
        private set
    var wipes = mutableListOf<String>()

    private fun makeManager() = ConversationManager(me, mls, api, repo, lock, clock, membership, escrow, syncOnce = { syncs += 1; processor.sync() })
    private fun makeProcessor() = InboxProcessor(mls, api, repo, lock, clock, manager, handler, membership, OwnDeviceNotices(repo, api, notifier))
    private fun makeOutbox() = Outbox(me, mls, api, repo, lock, clock, manager, escrow, syncOnce = { processor.sync() })
    private fun makeDeviceSync() = DeviceSync(
        me, mls, api, repo, lock, clock, manager, KeyPackagePool(mls, api, repo, lock, clock),
        wipeMls = { wipes += "mls" }, deleteSigningKey = { wipes += "signingKey" },
    )

    /** A process restart: new objects (no in-memory state), the same database, MLS storage and server. */
    fun restart() {
        manager = makeManager()
        processor = makeProcessor()
        outbox = makeOutbox()
        receipts = Receipts(outbox)
        deviceSync = makeDeviceSync()
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
    /** The server's escrow key pair; every device seals to its public key. */
    val escrowKeys = SoftwareHpke().generateKeyPair()
    val ana1 = TestDevice(server, "acc-ana", "ana1", escrowKeys.publicKey)
    val ana2 = TestDevice(server, "acc-ana", "ana2", escrowKeys.publicKey)
    val bob1 = TestDevice(server, "acc-bob", "bob1", escrowKeys.publicKey)
    val bob2 = TestDevice(server, "acc-bob", "bob2", escrowKeys.publicKey)
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
