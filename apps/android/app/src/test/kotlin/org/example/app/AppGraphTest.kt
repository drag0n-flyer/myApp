package org.example.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.isActive
import kotlin.test.assertNotNull
import org.example.app.core.calls.NetworkType
import org.example.app.auth.VaultSecretStore
import org.example.app.auth.WrappingKeyVault
import org.example.app.contracts.ConnectionState
import org.example.app.contracts.FakeMlsEngine
import org.example.app.contracts.LiveSocket
import org.example.app.contracts.SecretNames
import org.example.app.contracts.ServerEvent
import org.example.app.core.account.AccountState
import org.example.app.core.account.SampleAccountService
import org.example.app.core.chats.SampleChatStore
import org.example.app.live.LivePlatform
import org.example.app.live.SocketHandle
import org.example.app.messaging.LiveChatStore
import org.example.app.net.Session
import org.example.app.net.SessionStore
import org.example.app.net.account.LiveAccountService
import org.example.app.store.AppDatabase
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppGraphTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** A software stand-in for the Keystore key (Robolectric has no Keystore). */
    private class SoftVault : WrappingKeyVault {
        var key: SecretKey? = null
        var deletes = 0
        override val backing = "tee"
        override fun encryptCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key!!) }
        override fun decryptCipher(nonce: ByteArray): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key!!, GCMParameterSpec(128, nonce)) }
        override fun hasKey() = key != null
        override fun createKey() { if (key == null) key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        override fun deleteKey() { key = null; deletes++ }
    }

    private class FakeSocket : LiveSocket {
        var running = false
        var starts = 0
        val incoming = kotlinx.coroutines.channels.Channel<ServerEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        override val events: Flow<ServerEvent> = incoming.receiveAsFlow()
        override val states = MutableStateFlow(ConnectionState.Disconnected)
        override suspend fun send(type: String, data: JsonObject): JsonObject? = null
    }

    private val vault = SoftVault()
    private val root = File(context.noBackupFilesDir, "graph-test").apply { deleteRecursively() }
    private val cache = File(context.cacheDir, "graph-test").apply { deleteRecursively() }
    private val socket = FakeSocket()
    private val requests = AtomicInteger(0)

    /** Every request fails at once and is counted unless [answer] gives a response: the graph must build without any. */
    private var answer: (okhttp3.Request) -> Pair<Int, String>? = { null }
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        requests.incrementAndGet()
        val (code, body) = answer(chain.request()) ?: throw IOException("offline")
        okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private var notificationsCancelled = 0
    private var onCancelNotifications: () -> Unit = {}

    private fun platform() = LivePlatform(
        filesDir = root,
        cacheDir = cache,
        databaseName = "graph-test.db",
        secrets = { dir -> VaultSecretStore(vault, dir) {} },
        openDatabase = { name, _ -> Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build() },
        likeFallback = true,
        mls = { identity, dir, _ ->
            dir.mkdirs()
            File(dir, "state").writeText("group state")
            FakeMlsEngine(identity)
        },
        hpke = { error("no HPKE in unit tests") },
        http = http,
        socket = { _ -> SocketHandle(socket, start = { socket.running = true; socket.starts++ }, stop = { socket.running = false }) },
        deviceName = "Test phone",
        cancelNotifications = { notificationsCancelled++; onCancelNotifications() },
        network = { NetworkType.Mobile },
    )

    private val db get() = context.getDatabasePath("graph-test.db")
    private val marker get() = File(root, "wipe.pending")

    private fun saveSession(device: String = "dev-1") {
        SessionStore(VaultSecretStore(vault, File(root, "secrets")) {}).save(Session("chat.example.org", "acc-me", device, "tok"))
    }

    private fun signedIn(): AppGraph {
        saveSession()
        return AppGraph(context, sampleData = false, scope = scope, platform = platform())
    }

    /** A signed-in graph with its session open and some data on disk. */
    private fun opened(): Pair<AppGraph, org.example.app.live.LiveSession> = runBlocking {
        val graph = signedIn()
        val session = assertNotNull(graph.openSession())
        session.chatStore.settle()
        File(root, "media").apply { mkdirs() }.resolve("blob.enc").writeText("x")
        assertTrue(db.exists())
        assertTrue(File(root, "mls/state").exists())
        assertTrue(File(root, "secrets/${SecretNames.DB_KEY}").exists())
        graph to session
    }

    private fun assertAllGone() {
        assertFalse(db.exists(), "database")
        assertFalse(File(root, "mls").exists(), "mls/")
        assertFalse(File(root, "media").exists(), "media/")
        assertTrue(File(root, "secrets").listFiles().isNullOrEmpty(), "secrets/")
        assertNull(vault.key, "Keystore key")
    }

    @Test
    fun sampleDataSelectsSampleServices() {
        // The launch default is live; only the `sampleData` extra selects the sample services.
        assertFalse(MainActivity.usesSampleData(android.content.Intent()))
        assertTrue(MainActivity.usesSampleData(android.content.Intent().putExtra(MainActivity.EXTRA_SAMPLE_DATA, true)))
        val graph = AppGraph(context, sampleData = true, scope = scope, platform = platform())
        assertIs<SampleAccountService>(graph.accountService)
        assertIs<SampleChatStore>(graph.chatStore)
        assertTrue(graph.callsAvailable)
        val live = AppGraph(context, sampleData = false, scope = scope, platform = platform())
        assertFalse(live.accountService is SampleAccountService)
        assertFalse(live.chatStore is SampleChatStore)
        assertEquals(0, requests.get())
        assertFalse(File(root, "secrets").exists())
    }

    @Test
    fun liveGraphBuildsWithoutNetwork() {
        val graph = AppGraph(context, sampleData = false, scope = scope, platform = platform())
        assertEquals(AccountState.SignedOut, graph.accountState)
        graph.setVisible(true)
        assertEquals(0, requests.get())
        assertEquals(0, socket.starts)
        assertNull(graph.liveSession)
    }

    /** Nothing in the live build reads a sample value: host, invite, calls, network and settings are live. */
    @Test
    fun liveModeReadsNoSampleConstant() {
        val (graph, _) = opened()
        assertEquals("chat.example.org", graph.serverPolicy.host)
        assertFalse(graph.serverPolicy.host == "home.example.org")
        assertNull(graph.inviteUrl)
        assertFalse(graph.callsAvailable)
        assertEquals(NetworkType.Mobile, graph.network)
        assertEquals(org.example.app.core.settings.UserSettings.initial(graph.serverPolicy), graph.settingsStore.settings.value)
        assertIs<LiveChatStore>(graph.chatStore)
    }

    @Test
    fun revokedWipesEverything() = runBlocking {
        val (graph, session) = opened()
        graph.wipeAfterRevocation()
        assertAllGone()
        assertFalse(marker.exists(), "marker cleared")
        assertTrue(notificationsCancelled > 0, "notifications")
        assertFalse(session.scope.isActive, "the old account's collectors")
        assertEquals(AccountState.SignedOut, graph.accountState)
        assertNull(graph.liveSession)
        assertFalse(socket.running)
    }

    /** A kill after the marker: the next launch finishes the wipe before any data or key is opened. */
    @Test
    fun killAfterMarkerResumesWipe() = runBlocking {
        val (old, session) = opened()
        marker.writeText("full")
        session.database.close() // the process dies; the next one starts from disk
        val graph = AppGraph(context, sampleData = false, scope = scope, platform = platform())
        assertEquals(AccountState.SignedOut, graph.accountState)
        assertNull(graph.openSession())
        assertAllGone()
        assertFalse(marker.exists())
        assertIs<org.example.app.live.EmptyChatStore>(graph.chatStore)
        Unit
    }

    /** A pending wipe is finished before a sign-in starts, so a new account never gets the old database key. */
    @Test
    fun pendingWipeRunsBeforeSignIn() = runBlocking {
        val (_, session) = opened()
        marker.writeText("full")
        session.database.close()
        val graph = AppGraph(context, sampleData = false, scope = scope, platform = platform())
        runCatching { graph.accountService.connect("chat.example.org") }
        assertAllGone()
        assertFalse(marker.exists())
    }

    @Test
    fun failingStepDoesNotSkipLaterSteps() = runBlocking {
        val (graph, _) = opened()
        onCancelNotifications = { throw IllegalStateException("notification service gone") }
        assertTrue(runCatching { graph.wipeAfterRevocation() }.isFailure)
        assertAllGone()
        assertTrue(marker.exists(), "the marker stays until a wipe succeeds")
        assertNull(graph.openSession(), "nothing opens while the wipe is pending")
        onCancelNotifications = {}
        graph.finishPendingWipe()
        assertFalse(marker.exists())
    }

    @Test
    fun noSessionDuringWipe() = runBlocking {
        val (graph, _) = opened()
        var opened: Any? = "not checked"
        var state: AccountState? = null
        onCancelNotifications = { opened = runBlocking { graph.openSession() }; state = graph.accountState }
        graph.wipeAfterRevocation()
        assertNull(opened)
        assertEquals(AccountState.SignedOut, state)
    }

    /** Between a session's end and onboarding the screens still read a store: it is empty, never a crash. */
    @Test
    fun chatStoreIsSafeAfterTheSessionEnds() = runBlocking {
        val (graph, _) = opened()
        graph.wipeAfterRevocation()
        val store = graph.chatStore
        assertIs<org.example.app.live.EmptyChatStore>(store)
        assertTrue(store.chats.value.isEmpty())
        assertEquals("", store.me.id)
        assertTrue(store.messages("anything", null, 10).isEmpty())
    }

    private val unauthorized = """{"error":{"code":"unauthorized","message":"x"}}"""

    /** A 401 that `GET /v1/me` confirms is the server's proof: onSignedOut wipes. */
    @Test
    fun confirmed401Wipes() = runBlocking {
        val (graph, _) = opened()
        answer = { r -> if (r.url.encodedPath in setOf("/v1/devices", "/v1/me")) 401 to unauthorized else null }
        runCatching { graph.accountService.devices() }
        waitFor { vault.key == null && !marker.exists() }
        assertAllGone()
        assertEquals(AccountState.SignedOut, graph.accountState)
    }

    /** A 401 that the second call does not confirm signs out but keeps the data. */
    @Test
    fun unconfirmed401SignsOutAndKeepsData() = runBlocking {
        val (graph, _) = opened()
        val ends = graph.sessionEnds.value
        answer = { r ->
            when (r.url.encodedPath) {
                "/v1/devices" -> 401 to unauthorized
                "/v1/me" -> 200 to """{"accountID":"acc-me"}"""
                else -> null
            }
        }
        runCatching { graph.accountService.devices() }
        waitFor { graph.sessionEnds.value > ends }
        assertEquals(AccountState.SignedOut, graph.accountState)
        assertNull(graph.liveSession)
        assertTrue(db.exists(), "data kept")
        assertTrue(File(root, "mls/state").exists())
        assertFalse(marker.exists())
        assertTrue(vault.key != null)
    }

    /** `device.revoked` is only a hint and never wipes; the socket closing with 4003 (Revoked) does. */
    @Test
    fun onlyTheServersProofWipes() = runBlocking {
        val (graph, _) = opened()
        graph.setVisible(true)
        socket.incoming.send(ServerEvent.DeviceRevoked("dev-1"))
        kotlinx.coroutines.delay(500)
        assertEquals(AccountState.SignedIn, graph.accountState)
        assertTrue(db.exists())

        socket.states.value = ConnectionState.Revoked
        waitFor { vault.key == null && !marker.exists() && graph.liveSession == null }
        assertAllGone()
    }

    /** Another account signing in on this device never opens the previous account's data. */
    @Test
    fun anotherDevicesDataIsWipedBeforeOpening() = runBlocking {
        val (_, session) = opened()
        session.database.close()
        saveSession(device = "dev-2")
        val graph = AppGraph(context, sampleData = false, scope = scope, platform = platform())
        val fresh = assertNotNull(graph.openSession())
        assertTrue(fresh.chatStore.chats.value.isEmpty())
        assertFalse(File(root, "media").exists(), "old media gone")
    }

    @Test
    fun socketOpenOnlyWhileVisibleOrInCall() {
        val (graph, _) = opened()
        assertFalse(socket.running, "not visible yet")
        graph.setVisible(true)
        assertTrue(socket.running, "visible")
        graph.setVisible(false)
        assertFalse(socket.running, "in the background")
        graph.setInCall(true)
        assertTrue(socket.running, "in a call in the background")
        graph.setVisible(true)
        graph.setInCall(false)
        assertTrue(socket.running, "visible after the call")
        graph.setVisible(false)
        assertFalse(socket.running, "background after the call")
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        kotlinx.coroutines.withTimeout(10_000) { while (!condition()) kotlinx.coroutines.delay(50) }
    }
}
