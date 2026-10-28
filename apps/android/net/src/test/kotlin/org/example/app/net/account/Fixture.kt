package org.example.app.net.account

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.example.app.contracts.Hpke
import org.example.app.contracts.MemorySecretStore
import org.example.app.contracts.SecretNames
import org.example.app.contracts.SecretStore
import org.example.app.contracts.SnapshotSource
import org.example.app.net.ServerClient
import org.example.app.net.Session
import org.example.app.net.SessionStore

class FakePasskeys : Passkeys {
    var created: String? = null
    var asserted: String? = null
    var failWith: Exception? = null
    override suspend fun create(optionsJson: String): String {
        failWith?.let { throw it }
        created = optionsJson
        return """{"id":"cred1","type":"public-key","response":{"attestationObject":"AA"}}"""
    }
    override suspend fun get(optionsJson: String): String {
        failWith?.let { throw it }
        asserted = optionsJson
        return """{"id":"cred1","type":"public-key","response":{"authenticatorData":"AA","signature":"AA"}}"""
    }
}

class FakeSnapshots : SnapshotSource {
    var exports = 0
    var imported: ByteArray? = null
    var imports = 0
    var data = """{"v":1}""".encodeToByteArray()
    override suspend fun export(): ByteArray { exports++; return data }
    override suspend fun import(bytes: ByteArray) { imports++; imported = bytes }
}

class FakeApproval(var outcome: ApprovalOutcome = ApprovalOutcome.Success) : ApprovalConfirmer {
    var asked = 0
    override suspend fun confirm(): ApprovalOutcome { asked++; return outcome }
}

/** A store whose writes can be made to fail the way a busy Keystore does. */
class FlakyStore(private val inner: SecretStore = MemorySecretStore()) : SecretStore by inner {
    var failSaves = 0
    var saves = 0
    override fun save(name: String, value: ByteArray) {
        saves++
        if (failSaves > 0) { failSaves--; throw org.example.app.contracts.SecretStoreUnavailable("busy") }
        inner.save(name, value)
    }
}

class Fixture(
    var passkeysSupported: Boolean = true,
    val approval: FakeApproval = FakeApproval(),
    val hpke: Hpke = ReferenceHpke(),
    val store: SecretStore = MemorySecretStore(),
    signedIn: Boolean = false,
    now: Instant = Instant.parse("2026-10-28T12:00:00Z"),
) {
    val server = FakeServer()
    val sessions = SessionStore(store)
    val http = OkHttpClient()
    val client = ServerClient(server.web.url("/"), sessions, http)
    val hostPort = server.web.url("/").let { "${it.host}:${it.port}" }
    val passkeys = FakePasskeys()
    val snapshots = FakeSnapshots()
    val sleeps = ArrayList<Long>()
    var clock: Instant = now
    var assetLinksSeen: String? = "links"

    init {
        if (signedIn) sessions.save(Session(hostPort, "a1", "d1", "tok"))
    }

    val service = LiveAccountService(
        client = client,
        sessionStore = sessions,
        passkeys = passkeys,
        hpke = hpke,
        snapshots = snapshots,
        deviceName = "Pixel 8",
        biometrics = approval,
        passkeySupport = PasskeySupportCheck { _, _ -> passkeysSupported },
        makeClient = { addr -> if (addr.hostPort == hostPort) client else error("unexpected host ${addr.hostPort}") },
        sleep = { ms -> sleeps += ms },
        clock = { clock },
    )

    fun serverInfo(protocol: Int = 1) = server.json(
        "GET /v1/server",
        """{"protocol":$protocol,"host":"chat.test","requiresInvite":true,
          "policy":{"defaultMode":"auto","maxMode":"balanced","defaultIPHiding":"nonContacts"},
          "encryption":{"newConversations":"e2ee","escrowKey":null},
          "push":{"gateway":null,"vapidKey":null},
          "features":["linking"],"federation":false,"limits":{"attachmentBytes":104857600}}""",
    )

    fun newSession(token: String = "tok-new") =
        """{"accountID":"a1","deviceID":"d1","sessionToken":"$token"}"""

    fun storedSession(): Session? =
        store.load(SecretNames.SESSION)?.let {
            org.example.app.net.ServerJson.json.decodeFromString(Session.serializer(), it.decodeToString())
        }

    fun bodyJson(key: String): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(server.bodyOf(key)) as JsonObject

    fun shutdown() = server.shutdown()
}

