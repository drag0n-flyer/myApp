package org.example.app

import kotlinx.coroutines.flow.MutableSharedFlow
import org.example.app.calls.android.CallController
import org.example.app.calls.android.CallInfo
import org.example.app.calls.android.CallService
import org.example.app.calls.android.Ringer
import org.example.app.calls.android.telecom.PlainCallController
import org.example.app.calls.android.telecom.FallbackCallController
import org.example.app.calls.policy.AudioProfile
import org.example.app.calls.policy.IceState
import org.example.app.calls.policy.IceTransportPolicy
import org.example.app.calls.policy.MediaStats
import org.example.app.calls.policy.VideoTier
import org.example.app.calls.session.IceServer
import org.example.app.calls.session.MediaEvent
import org.example.app.calls.session.MediaSession
import org.example.app.calls.session.MediaSessionFactory
import org.example.app.contracts.DataMode
import org.example.app.contracts.IceCandidate
import org.example.app.contracts.RingOutcome
import org.example.app.contracts.CallSealException
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.CallSignalSealer
import org.example.app.contracts.OpenedCallSignal
import org.example.app.core.calls.CallEndReason
import org.example.app.core.calls.CallPhase
import org.example.app.core.settings.NoiseSuppression
import org.robolectric.Shadows.shadowOf
import org.example.app.calls.android.CallsRuntime
import org.example.app.calls.android.LiveCallEngine
import org.example.app.core.calls.SimulatedCallEngine
import org.example.app.live.SessionCallEngine
import org.example.app.ui.calls.CallHistoryViewModel
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
import kotlin.test.assertFailsWith
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.test.assertSame
import kotlin.test.assertNotNull
import org.example.app.core.calls.NetworkType
import org.example.app.auth.VaultSecretStore
import org.example.app.auth.WrappingKeyVault
import org.example.app.contracts.ConnectionState
import org.example.app.contracts.FakeMlsEngine
import org.example.app.contracts.LiveSocket
import org.example.app.contracts.SecretNames
import org.example.app.contracts.ServerEvent
import org.example.app.core.account.AccountError
import org.example.app.core.account.AccountState
import org.example.app.core.account.ConnectionStatus
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
        assertEquals(NetworkType.Mobile, graph.network)
        assertEquals(org.example.app.core.settings.UserSettings.initial(graph.serverPolicy), graph.settingsStore.settings.value)
        assertIs<LiveChatStore>(graph.chatStore)
    }

    /** Live calls run on `LiveCallEngine` through the open session; the Calls tab shows only real calls (none yet). */
    @Test
    fun liveModeUsesLiveCallEngine() {
        val (graph, session) = opened()
        assertTrue(graph.callsAvailable)
        assertFalse(graph.callEngine is SimulatedCallEngine)
        assertIs<LiveCallEngine>(session.calls)
        assertSame(session.calls, assertIs<SessionCallEngine>(graph.callEngine).current)
        assertSame(graph.callEngine, CallsRuntime.engine)
        assertNull(graph.callEngine.state.value)
        assertTrue(CallHistoryViewModel(graph.chatStore).records.value.isEmpty())
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

    /**
     * A revocation wipe that starts and finishes after an open read the stored session but before it took the lock
     * leaves nothing to open: no session on the wiped data, none with the revoked token.
     */
    @Test
    fun wipeBetweenTheReadAndTheLockOpensNothing() = runBlocking {
        val graph = signedIn()
        graph.beforeOpenLock = { graph.beforeOpenLock = {}; graph.wipeAfterRevocation() }
        assertNull(graph.openSession())
        assertNull(graph.liveSession)
        assertFalse(db.exists(), "database")
        assertEquals(AccountState.SignedOut, graph.accountState)
    }

    /**
     * A revocation wipe that runs after an open read `pending` and before it took the revocation count, and that could
     * not sign out or finish, leaves only the marker behind: the open sees it and opens nothing on the part-wiped data.
     */
    @Test
    fun failedWipeBetweenThePendingCheckAndTheSnapshotOpensNothing() = runBlocking {
        val graph = signedIn()
        graph.beforeRevocationSnapshot = {
            graph.beforeRevocationSnapshot = {}
            root.mkdirs()
            marker.writeText("full") // what a wipe whose sign-out and deletes failed leaves
        }
        assertNull(graph.openSession())
        assertNull(graph.liveSession)
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
    fun aDifferentAccountSigningInAfterADataWipeGetsDefaultSettings() = runBlocking {
        val (graph, session) = opened()
        graph.settingsStore.update { it.copy(readReceipts = false, ipHiding = org.example.app.core.settings.IPHiding.Always) }
        session.database.close()
        saveSession(device = "dev-2")
        val next = AppGraph(context, sampleData = false, scope = scope, platform = platform())
        assertNotNull(next.openSession())
        assertEquals(org.example.app.core.settings.UserSettings.initial(next.serverPolicy), next.settingsStore.settings.value)
        assertNull(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("settings", null))
    }

    @Test
    fun theSameAccountSigningBackInKeepsItsSettings() = runBlocking {
        val (graph, session) = opened()
        graph.settingsStore.update { it.copy(readReceipts = false) }
        session.database.close()
        val next = AppGraph(context, sampleData = false, scope = scope, platform = platform())
        assertNotNull(next.openSession())
        assertEquals(false, next.settingsStore.settings.value.readReceipts)
    }

    @Test
    fun anAccountActionErrorFromBeforeIsNotShownAfterTheNextSignIn() = runBlocking {
        val (graph, _) = opened()
        answer = { r ->
            when {
                r.method == "GET" && r.url.encodedPath == "/v1/devices" -> 200 to
                    """{"devices":[{"id":"dev-1","name":"Test phone","platform":"android","createdAt":"2026-01-01T00:00:00Z","current":true}]}"""
                r.method == "DELETE" && r.url.encodedPath == "/v1/devices/dev-1" -> 204 to ""
                else -> null
            }
        }
        onCancelNotifications = { throw IllegalStateException("notification service gone") } // a wipe step fails after the revoke
        graph.accountActions.start(org.example.app.ui.settings.ProfileAction.SigningOut)
        var waited = 0
        while (graph.accountActions.error.value == null && waited++ < 300) Thread.sleep(10)
        assertNotNull(graph.accountActions.error.value, "the failure was kept")
        onCancelNotifications = {}
        graph.finishPendingWipe()
        runCatching { graph.accountService.connect("chat.example.org") } // the next sign-in starts here
        assertNull(graph.accountActions.error.value, "a new session starts without the old error")
    }

    /** Two opens at once after a device switch (UI plus push): one wipe, one live session, its data intact. */
    @Test
    fun concurrentOpensAfterDeviceSwitchLeaveOneSession() = runBlocking {
        val (_, old) = opened()
        old.database.close()
        saveSession(device = "dev-2")
        val graph = AppGraph(context, sampleData = false, scope = scope, platform = platform())
        val opens = List(4) { async(Dispatchers.IO) { graph.openSession() } }.awaitAll()
        val session = assertNotNull(graph.liveSession)
        opens.forEach { assertSame(session, it) }
        assertTrue(session.scope.isActive, "the open session was not wiped")
        assertFalse(marker.exists())
        assertTrue(vault.key != null, "database key kept")
        assertTrue(db.exists(), "database kept")
        session.chatStore.settle()
        assertEquals(AccountState.SignedIn, graph.accountState)
    }

    /**
     * A second open (a push ring) that arrives while the first one wipes another device's data waits for that open
     * and gets its session: the open's own wipe is not a revocation, so it never answers "not signed in".
     */
    @Test
    fun openDuringTheSwitchWipeWaitsForTheSession() = runBlocking {
        val (_, old) = opened()
        old.database.close()
        saveSession(device = "dev-2")
        val graph = AppGraph(context, sampleData = false, scope = scope, platform = platform())
        var second: kotlinx.coroutines.Deferred<org.example.app.live.LiveSession?>? = null
        onCancelNotifications = {
            onCancelNotifications = {}
            val during = scope.async(Dispatchers.IO) { graph.openSession() }
            second = during
            // Bounded: before the fix the second open returned null at once; after it, it waits for this open.
            runBlocking { kotlinx.coroutines.withTimeoutOrNull(1_000) { during.join() } }
        }
        val first = assertNotNull(graph.openSession())
        assertSame(first, assertNotNull(second, "the wipe ran inside the open").await())
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

    // ---- Connection status, account actions, settings (usable-app Task 4)

    @Test
    fun connectionStatusFollowsTheSocketAndTheNetwork() {
        val (graph, _) = opened()
        val wifi = Any()
        assertEquals(ConnectionStatus.Connecting, graph.connectionStatus.value)
        socket.states.value = ConnectionState.Connected
        assertEquals(ConnectionStatus.Connected, graph.connectionStatus.value)
        graph.onNetworkAvailable(wifi)
        graph.onNetworkLost(wifi)
        assertEquals(ConnectionStatus.Offline, graph.connectionStatus.value)
        graph.onNetworkAvailable(wifi)
        assertEquals(ConnectionStatus.Connected, graph.connectionStatus.value)
        socket.states.value = ConnectionState.Disconnected
        assertEquals(ConnectionStatus.Connecting, graph.connectionStatus.value)
    }

    @Test
    fun switchingNetworksDoesNotFlipToNoConnection() {
        val (graph, _) = opened()
        val wifi = Any()
        val mobile = Any()
        socket.states.value = ConnectionState.Connected
        graph.onNetworkAvailable(wifi)
        graph.onNetworkAvailable(mobile) // mobile becomes the default first...
        graph.onNetworkLost(wifi) // ...then the old one is reported lost
        assertEquals(ConnectionStatus.Connected, graph.connectionStatus.value)
        graph.onNetworkLost(mobile)
        assertEquals(ConnectionStatus.Offline, graph.connectionStatus.value)
    }

    @Test
    fun aReplacedOrEndedSessionIsOfflineNotConnectingForever() {
        assertEquals(ConnectionStatus.Offline, statusOf(ConnectionState.Replaced, sessionGone = false, networkUp = true))
        assertEquals(ConnectionStatus.Offline, statusOf(ConnectionState.Revoked, sessionGone = false, networkUp = true))
        assertEquals(ConnectionStatus.Offline, statusOf(null, sessionGone = true, networkUp = true))
        assertEquals(ConnectionStatus.Connecting, statusOf(ConnectionState.Disconnected, sessionGone = false, networkUp = true))
        assertEquals(ConnectionStatus.Connecting, statusOf(null, sessionGone = false, networkUp = true), "still opening")
        assertEquals(ConnectionStatus.Connected, statusOf(ConnectionState.Connected, sessionGone = false, networkUp = true))
    }

    @Test
    fun anEndedSessionShowsOffline() = runBlocking {
        val (graph, _) = opened()
        socket.states.value = ConnectionState.Connected
        graph.wipeAfterRevocation()
        assertEquals(ConnectionStatus.Offline, graph.connectionStatus.value)
    }

    @Test
    fun sampleDataIsAlwaysConnected() {
        val graph = AppGraph(context, sampleData = true, scope = scope, platform = platform())
        assertEquals(ConnectionStatus.Connected, graph.connectionStatus.value)
        val n = Any()
        graph.onNetworkAvailable(n)
        graph.onNetworkLost(n)
        assertEquals(ConnectionStatus.Connected, graph.connectionStatus.value)
    }

    @Test
    fun aFullWipeResetsTheSettingsForTheNextAccount() = runBlocking {
        val (graph, _) = opened()
        graph.settingsStore.update { it.copy(readReceipts = false, findByEmail = true) }
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        assertEquals(false, org.example.app.ui.settings.SettingsStore(prefs).settings.value.readReceipts, "saved")
        graph.wipeAfterRevocation()
        assertEquals(true, graph.settingsStore.settings.value.readReceipts)
        assertEquals(false, graph.settingsStore.settings.value.findByEmail)
        assertEquals(org.example.app.core.settings.UserSettings(), org.example.app.ui.settings.SettingsStore(prefs).settings.value)
    }

    @Test
    fun theSettingsAreGoneBeforeTheWipeMarkerCanBeDeleted() = runBlocking {
        val (graph, _) = opened()
        graph.settingsStore.update { it.copy(readReceipts = false) }
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        var storedWhileMarkerHeld: String? = "not checked"
        // The first delete step runs while the marker is still there: a kill from here on must not leave old settings.
        onCancelNotifications = { assertTrue(marker.exists()); storedWhileMarkerHeld = prefs.getString("settings", null) }
        graph.wipeAfterRevocation()
        assertNull(storedWhileMarkerHeld, "settings were still stored when the delete started")
    }

    @Test
    fun aWipeResumedAtTheNextLaunchResetsTheSettings() = runBlocking {
        val (graph, session) = opened()
        graph.settingsStore.update { it.copy(readReceipts = false) }
        session.database.close()
        marker.writeText("full") // a kill left the wipe pending
        val next = AppGraph(context, sampleData = false, scope = scope, platform = platform())
        next.finishPendingWipe()
        assertEquals(true, next.settingsStore.settings.value.readReceipts)
        assertNull(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("settings", null))
        assertFalse(marker.exists())
    }

    /** Runs two saves on IO threads; the first PATCH waits on a gate so the second queues behind it. */
    private fun saveFindByEmailTwice(first: Boolean, second: Boolean, patches: List<Int>): Pair<Result<Unit>, Result<Unit>> = runBlocking {
        val (graph, _) = opened()
        graph.settingsStore.update { it.copy(findByEmail = false) }
        val gate = java.util.concurrent.CountDownLatch(1)
        val seen = AtomicInteger(0)
        answer = { r ->
            if (r.method == "PATCH" && r.url.encodedPath == "/v1/me/settings") {
                val n = seen.getAndIncrement()
                if (n == 0) gate.await()
                patches[n] to (if (patches[n] == 204) "" else """{"error":{"code":"internal"}}""")
            } else null
        }
        val a = async(Dispatchers.IO) { runCatching { graph.saveFindByEmail(first) } }
        while (seen.get() == 0) Thread.sleep(10)
        val b = async(Dispatchers.IO) { runCatching { graph.saveFindByEmail(second) } }
        Thread.sleep(300) // the second save is waiting for the first one's turn
        gate.countDown()
        val results = a.await() to b.await()
        lastFindByEmail = graph.settingsStore.settings.value.findByEmail
        results
    }

    private var lastFindByEmail: Boolean? = null

    @Test
    fun twoQueuedFindByEmailSavesThatBothFailEndMatchingTheServer() {
        val (a, b) = saveFindByEmailTwice(first = true, second = false, patches = listOf(500, 500))
        assertTrue(a.isFailure && b.isFailure)
        assertEquals(false, lastFindByEmail, "the server still has false")
    }

    @Test
    fun aFailedFindByEmailSaveFollowedByASuccessfulOneEndsWithTheSecondsValue() {
        val (a, b) = saveFindByEmailTwice(first = true, second = true, patches = listOf(500, 204))
        assertTrue(a.isFailure)
        assertTrue(b.isSuccess)
        assertEquals(true, lastFindByEmail)
    }

    @Test
    fun settingsAreResetOnSignOutAndDeleteToo() = runBlocking {
        val (graph, _) = opened()
        graph.settingsStore.update { it.copy(readReceipts = false) }
        answer = { r -> if (r.method == "DELETE" && r.url.encodedPath == "/v1/me") 204 to "" else null }
        graph.deleteAccount()
        assertEquals(true, graph.settingsStore.settings.value.readReceipts)
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        assertEquals(null, prefs.getString("settings", null))
    }

    @Test
    fun aNotificationForAChatThatDoesNotExistIsDropped() {
        val graph = AppGraph(context, sampleData = true, scope = scope, platform = platform())
        assertEquals("chat-riya", graph.openableChat("chat-riya"))
        assertNull(graph.openableChat("someone-elses-id"))
        assertNull(graph.openableChat(""))
        assertNull(graph.openableChat(null))
    }

    @Test
    fun aNotificationForAMessageRequestOpens() {
        val graph = AppGraph(context, sampleData = true, scope = scope, platform = platform())
        val request = graph.chatStore.requests.value.first().id
        assertEquals(request, graph.openableChat(request))
    }

    @Test
    fun signOutWithNoConnectionSaysSoAndKeepsEverything() = runBlocking {
        val (graph, session) = opened() // every request fails: offline
        val ends = graph.sessionEnds.value
        assertFailsWith<AccountError.Network> { graph.signOut() }
        assertTrue(db.exists(), "database kept")
        assertTrue(File(root, "mls/state").exists())
        assertTrue(File(root, "media/blob.enc").exists())
        assertTrue(vault.key != null, "Keystore key kept")
        assertFalse(marker.exists(), "no wipe was started")
        assertSame(session, graph.liveSession)
        assertEquals(AccountState.SignedIn, graph.accountState)
        assertEquals(ends, graph.sessionEnds.value, "no return to onboarding")
    }

    @Test
    fun deleteAccountWithNoConnectionSaysSoAndKeepsEverything() = runBlocking {
        val (graph, session) = opened()
        val ends = graph.sessionEnds.value
        assertFailsWith<AccountError.Network> { graph.deleteAccount() }
        assertTrue(db.exists())
        assertTrue(vault.key != null)
        assertFalse(marker.exists())
        assertSame(session, graph.liveSession)
        assertEquals(AccountState.SignedIn, graph.accountState)
        assertEquals(ends, graph.sessionEnds.value)
    }

    @Test
    fun aServerErrorOnSignOutKeepsEverythingToo() = runBlocking {
        val (graph, _) = opened()
        answer = { r -> if (r.url.encodedPath == "/v1/devices") 500 to """{"error":{"code":"internal"}}""" else null }
        assertFailsWith<AccountError.Server> { graph.signOut() }
        assertTrue(db.exists())
        assertTrue(vault.key != null)
    }

    @Test
    fun signOutRevokesThisDeviceThenWipesAndReturnsToOnboarding() = runBlocking {
        val (graph, _) = opened()
        val ends = graph.sessionEnds.value
        val seen = mutableListOf<String>()
        answer = { r ->
            seen += "${r.method} ${r.url.encodedPath}"
            when {
                r.method == "GET" && r.url.encodedPath == "/v1/devices" -> 200 to
                    """{"devices":[{"id":"dev-0","name":"Other","platform":"ios","createdAt":"2026-01-01T00:00:00Z","current":false},""" +
                    """{"id":"dev-1","name":"Test phone","platform":"android","createdAt":"2026-01-01T00:00:00Z","current":true}]}"""
                r.method == "DELETE" && r.url.encodedPath == "/v1/devices/dev-1" -> 204 to ""
                else -> null
            }
        }
        graph.signOut()
        assertEquals(listOf("GET /v1/devices", "DELETE /v1/devices/dev-1"), seen.filter { "/v1/devices" in it })
        assertAllGone()
        assertEquals(AccountState.SignedOut, graph.accountState)
        assertTrue(graph.sessionEnds.value > ends)
    }

    @Test
    fun deleteAccountWipesOnlyAfterTheServerDeletedIt() = runBlocking {
        val (graph, _) = opened()
        val ends = graph.sessionEnds.value
        answer = { r -> if (r.method == "DELETE" && r.url.encodedPath == "/v1/me") 204 to "" else null }
        graph.deleteAccount()
        assertAllGone()
        assertEquals(AccountState.SignedOut, graph.accountState)
        assertTrue(graph.sessionEnds.value > ends)
    }

    @Test
    fun liveSettingsSurviveARestart() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        val store = AppGraph(context, sampleData = false, scope = scope, platform = platform()).settingsStore
        store.update { it.copy(readReceipts = false) }
        assertEquals(false, AppGraph(context, sampleData = false, scope = scope, platform = platform()).settingsStore.settings.value.readReceipts)
        assertEquals(true, AppGraph(context, sampleData = true, scope = scope, platform = platform()).settingsStore.settings.value.readReceipts, "sample data stays in memory")
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ---- Calls end with the session (sign-out, wipe)

    /** The Telecom side: the connections it holds open. [refuse] sends every call to the plain controller. */
    private class TestTelecom(val refuse: Boolean) : CallController {
        val open = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        override fun reportIncoming(call: CallInfo) { if (refuse) throw IllegalStateException("refused"); open += call.callId }
        override fun reportOutgoing(call: CallInfo) { if (refuse) throw IllegalStateException("refused"); open += call.callId }
        override fun reportAnswered(callId: String, video: Boolean) = Unit
        override fun reportActive(callId: String) = Unit
        override fun reportEnded(callId: String, reason: CallEndReason) { open -= callId }
        override fun reportMissed(call: CallInfo) = Unit
    }

    private class TestRinger : Ringer {
        @Volatile var ringing = false
        override fun start() { ringing = true }
        override fun stop() { ringing = false }
    }

    private class TestMedia : MediaSession {
        override val events = MutableSharedFlow<MediaEvent>(extraBufferCapacity = 8)
        @Volatile var closed = false
        private val sdp = "v=0\r\na=fingerprint:sha-256 AA:BB\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
        override suspend fun configure(iceServers: List<IceServer>, policy: IceTransportPolicy, video: Boolean) = Unit
        override suspend fun makeOffer(receive: AudioProfile, iceRestart: Boolean) = sdp
        override suspend fun acceptOffer(sdp: String, send: AudioProfile, receive: AudioProfile) = this.sdp
        override suspend fun acceptAnswer(sdp: String, send: AudioProfile) = Unit
        override suspend fun rollback() = Unit
        override suspend fun add(candidate: IceCandidate) = Unit
        override suspend fun setAudioMaxBitrate(bps: Int) = Unit
        override suspend fun setVideo(tier: VideoTier?, ceiling: DataMode) = Unit
        override suspend fun setMuted(on: Boolean) = Unit
        override suspend fun setNoiseSuppression(mode: NoiseSuppression) = Unit
        override suspend fun stats() = MediaStats()
        override suspend fun close() { closed = true }
    }

    /**
     * A signed-in graph with a call from Bob ringing (or, with [active], answered and connected), then [end]: the call
     * must leave nothing behind on the phone.
     */
    private fun callEndsWithSession(refuseTelecom: Boolean, active: Boolean, end: suspend (AppGraph) -> Unit) = runBlocking {
        val telecom = TestTelecom(refuseTelecom)
        val ringer = TestRinger()
        val media = TestMedia()
        answer = { r -> if (r.url.encodedPath == "/v1/turn") 200 to """{"iceServers":[],"ttl":60}""" else null }
        saveSession()
        val graph = AppGraph(
            context, sampleData = false, scope = scope, platform = platform(),
            makeCallController = { FallbackCallController(telecom, PlainCallController(it, ringer)) },
            callMedia = { _, _ -> MediaSessionFactory { media } },
            // The fake MLS engine has no 1:1 group with Bob to seal in: frames go out as plain bytes.
            callSealer = object : CallSignalSealer {
                override suspend fun seal(payload: CallSignalPayload, to: String) = ByteArray(1)
                override suspend fun open(data: ByteArray, from: String): OpenedCallSignal = throw CallSealException.Undecryptable
            },
        )
        val session = assertNotNull(graph.openSession())
        session.chatStore.settle()
        val app = shadowOf(context as android.app.Application)
        session.calls.ring(RingOutcome.Ring("c1", "bob", "Bob", video = false))
        waitFor { graph.callEngine.state.value?.phase == CallPhase.Incoming }
        if (refuseTelecom) {
            assertTrue(ringer.ringing)
            assertEquals(1, callNotifications())
        } else {
            assertEquals(setOf("c1"), telecom.open.toSet())
        }
        if (active) {
            graph.callEngine.answer(video = false)
            waitFor { graph.callEngine.state.value?.phase == CallPhase.Connecting }
            media.events.emit(MediaEvent.IceStateChanged(IceState.Connected))
            waitFor { graph.callEngine.state.value?.phase == CallPhase.Active }
            if (refuseTelecom) assertEquals(CallService::class.java.name, app.nextStartedService?.component?.className)
        } else {
            assertNull(app.peekNextStartedService(), "no service while it rings")
        }
        end(graph)
        assertTrue(telecom.open.isEmpty(), "Telecom connection")
        assertFalse(ringer.ringing, "ringtone")
        assertEquals(0, callNotifications(), "notification")
        if (active && refuseTelecom) assertEquals(CallService::class.java.name, app.nextStoppedService?.component?.className)
        assertNull(app.peekNextStartedService(), "service")
        assertTrue(media.closed, "media")
        assertNull(graph.callEngine.state.value)
    }

    private fun callNotifications(): Int =
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).allNotifications.count { it.channelId == "calls" }

    private val signOut: suspend (AppGraph) -> Unit = { graph ->
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
    }

    private val wipe: suspend (AppGraph) -> Unit = { it.wipeAfterRevocation() }

    @Test fun signOutWhileRingingEndsTheCall() = callEndsWithSession(refuseTelecom = false, active = false, end = signOut)
    @Test fun wipeWhileRingingEndsTheCall() = callEndsWithSession(refuseTelecom = true, active = false, end = wipe)
    @Test fun signOutDuringACallEndsIt() = callEndsWithSession(refuseTelecom = true, active = true, end = signOut)
    @Test fun wipeDuringACallEndsIt() = callEndsWithSession(refuseTelecom = false, active = true, end = wipe)

    private suspend fun waitFor(condition: () -> Boolean) {
        kotlinx.coroutines.withTimeout(10_000) { while (!condition()) kotlinx.coroutines.delay(50) }
    }
}
