package org.example.app

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.app.contracts.LiveSocket
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.app.auth.BiometricGate
import org.example.app.auth.CredentialManagerPasskeys
import org.example.app.auth.PasskeySupport
import org.example.app.contracts.Hpke
import org.example.app.contracts.ServerEvent
import org.example.app.contracts.SnapshotSource
import org.example.app.contracts.X25519KeyPair
import org.example.app.core.account.AccountService
import org.example.app.core.account.SampleAccountService
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.calls.SimulatedCallEngine
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import org.example.app.core.settings.UserSettings
import org.example.app.live.LivePlatform
import org.example.app.live.LiveSession
import org.example.app.messaging.sync.DeviceSync
import org.example.app.messaging.sync.LocalDevice
import org.example.app.messaging.sync.OwnDeviceWatcher
import org.example.app.net.ServerClient
import org.example.app.net.SessionStore
import org.example.app.net.account.ApprovalConfirmer
import org.example.app.net.account.ApprovalOutcome
import org.example.app.net.account.LiveAccountService
import org.example.app.net.account.PasskeySupportCheck
import org.example.app.net.account.Passkeys
import org.example.app.ui.settings.SettingsStore

private const val TAG = "AppGraph"

/**
 * Manual wiring of the services the screens use (plan Task I1). [sampleData] selects the sample services (UI tests,
 * `-sampleData`); otherwise the live ones over the Go server: accounts (`LiveAccountService`), and once signed in a
 * [LiveSession] with the MLS-backed chat store, the live socket and push.
 *
 * Calls stay on the [SimulatedCallEngine] until E6. The live socket is open only while the app is visible or a call
 * is active ([setVisible], [setInCall]); push wakes the app otherwise.
 */
class AppGraph(
    context: Context,
    val sampleData: Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val platform: LivePlatform = LivePlatform.android(context),
) {
    private val appContext = context.applicationContext

    // Sample values until the live server, settings and network monitor exist.
    val serverPolicy = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    /** The single source of truth for the user's settings; consumers read it as a flow. */
    val settingsStore = SettingsStore(UserSettings())
    val network = NetworkType.Wifi
    /** Signals that the device list may have changed (`device.revoked`); the linked devices screen reloads. */
    val deviceListChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Own-device work on the live connection (`OwnDeviceWatcher`): an inbox sync and the reconcile on every connect,
     * `device.revoked` (a hint only: it never wipes), and [wipe] when the socket closes with 4003. One mutex runs it all.
     */
    fun watchOwnDevices(
        socket: LiveSocket,
        deviceSync: DeviceSync,
        syncInbox: suspend () -> Unit,
        wipe: suspend () -> Unit = deviceSync::wipeLocalData,
    ) = OwnDeviceWatcher(
        scope = scope,
        syncInbox = syncInbox,
        reconcile = deviceSync::reconcileOwnDevices,
        handleRevoked = deviceSync::handleRevoked,
        refreshDevices = { deviceListChanged.emit(Unit) },
        wipe = wipe,
        log = { Log.w("OwnDevices", it) },
    ).start(socket.states, socket.events)

    // TODO(E6): the live call engine (`LiveCallEngine`) replaces the simulated one here, for both data modes.
    val callEngine: SimulatedCallEngine by lazy {
        SimulatedCallEngine(server = serverPolicy, settings = settingsStore.settings.value.call, network = network).also { engine ->
            settingsStore.settings.map { it.call }.distinctUntilChanged().onEach { engine.settings = it }.launchIn(scope)
            engine.state.map { it != null && !it.isEnded }.distinctUntilChanged().onEach { setInCall(it) }.launchIn(scope)
        }
    }

    /** The activity on screen, for the passkey and biometric prompts. Set by [MainActivity]. */
    @Volatile var activity: FragmentActivity? = null

    // ---- Accounts

    private val secrets by lazy { platform.secrets(platform.secretsDir) }
    private val sessions by lazy { SessionStore(secrets) }

    /** The Rust HPKE, loaded on first use (no native library is loaded to build the graph). */
    internal val hpke: Hpke = object : Hpke {
        private val real by lazy(platform.hpke)
        override fun seal(recipientPublicKey: ByteArray, info: String, plaintext: ByteArray) = real.seal(recipientPublicKey, info, plaintext)
        override fun open(keyPair: X25519KeyPair, info: String, sealed: ByteArray) = real.open(keyPair, info, sealed)
        override fun generateKeyPair() = real.generateKeyPair()
    }

    private val liveAccount: LiveAccountService by lazy {
        LiveAccountService(
            client = ServerClient(platform.defaultHost, sessions, platform.http),
            sessionStore = sessions,
            passkeys = PasskeysBridge(CredentialManagerPasskeys { activity }),
            hpke = hpke,
            snapshots = LinkSnapshots(),
            deviceName = platform.deviceName,
            biometrics = ApprovalConfirmer { confirmWithBiometrics() },
            passkeySupport = PasskeySupportCheck { host, json ->
                signingCertSha256()?.let { PasskeySupport.supported(host, json, appContext.packageName, it) } ?: false
            },
            makeClient = { address -> ServerClient(address, sessions, platform.http) },
        ).also { it.onSignedOut = { scope.launch { quietly("wipe") { wipeAfterRevocation() } } } }
    }

    private val sampleAccount by lazy { SampleAccountService() }
    private val sampleChats by lazy { SampleChatStore() }

    val accountService: AccountService get() = if (sampleData) sampleAccount else liveAccount

    // ---- Signed-in services

    /** The signed-in services; null while signed out. */
    @Volatile var liveSession: LiveSession? = null
        private set

    private val _sessionEnds = MutableStateFlow(0)
    /** Bumped when the session ended (server sign-out, revocation wipe): the UI goes back to onboarding. */
    val sessionEnds: StateFlow<Int> = _sessionEnds.asStateFlow()

    /** Sample data, or the signed-in device's chats. Read it only once signed in (AppRoot shows onboarding before). */
    val chatStore: ChatStore
        get() = if (sampleData) sampleChats else checkNotNull(openSession()) { "chatStore needs a signed-in account" }.chatStore

    /** Opens the signed-in services if the account is signed in; null otherwise. */
    fun openSession(): LiveSession? {
        if (sampleData) return null
        synchronized(this) {
            liveSession?.let { return it }
            val stored = sessions.current ?: return null
            val client = liveAccount.sessionClient ?: return null
            val session = LiveSession(
                LocalDevice(stored.accountId, stored.deviceId), client, secrets, platform, appContext, hpke, scope,
                readReceiptsEnabled = { settingsStore.settings.value.readReceipts },
            )
            liveSession = session
            start(session)
            return session
        }
    }

    private fun start(session: LiveSession) {
        scope.launch {
            quietly("start") { session.start() }
            quietly("push") { org.example.app.live.PushWiring.registerAfterSignIn(appContext, session.client) }
        }
        val live = session.socket.socket
        // The socket's events have one collector: the watcher. A pushed inbox item is read on the way through.
        // TODO(E6): call events go to `LiveCallEngine`.
        val events = live.events.onEach { if (it is ServerEvent.Inbox) quietly("sync") { session.chatStore.syncInbox() } }
        val socket = object : LiveSocket by live {
            override val events = events
        }
        watchOwnDevices(socket, session.deviceSync, syncInbox = { session.chatStore.syncInbox() }, wipe = { wipeAfterRevocation() })
        updateSocket()
    }

    private suspend fun quietly(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        }
    }

    private val wiping = Mutex()

    /**
     * This device was revoked (a 401, or the socket closed with 4003; never `device.revoked` alone): deletes the database, `mls/`, `media/`, the decrypted and outgoing caches, every secret
     * in `secrets/` and the Keystore key. Every step runs; the first failure is thrown at the end.
     */
    suspend fun wipeAfterRevocation() = wiping.withLock {
        var failure: Exception? = null
        fun step(body: () -> Unit) = try { body() } catch (e: Exception) { failure = failure ?: e }
        val session = synchronized(this) { liveSession.also { liveSession = null } }
        if (session != null) {
            step { session.socket.stop() }
            session.chatStore.shutdown()
            try { session.deviceSync.wipeLocalData() } catch (e: Exception) { failure = failure ?: e }
            step { session.database.close() }
        }
        step { if (!appContext.deleteDatabase(platform.databaseName) && appContext.getDatabasePath(platform.databaseName).exists()) error("database not deleted") }
        for (dir in listOf(platform.mlsDir, platform.mediaDir, File(platform.cacheDir, "open"), File(platform.cacheDir, "outgoing"))) {
            step { if (dir.exists() && !dir.deleteRecursively()) error("${dir.name} not deleted") }
        }
        if (!sampleData) step { liveAccount.signOutLocally() }
        step { secrets.wipeAll() } // the session, the database key, the signing key and the Keystore key
        _sessionEnds.value += 1
        failure?.let { throw it }
        Unit
    }

    // ---- The live socket

    private var visible = false
    private var inCall = false

    /** The app came to the foreground or left it. */
    fun setVisible(value: Boolean) = synchronized(this) { visible = value; updateSocket() }

    /** A call started or ended. */
    fun setInCall(value: Boolean) = synchronized(this) { inCall = value; updateSocket() }

    private fun updateSocket() = synchronized(this) {
        val socket = liveSession?.socket ?: return@synchronized
        if (visible || inCall) socket.start() else socket.stop()
    }

    // ---- Platform adapters

    private suspend fun confirmWithBiometrics(): ApprovalOutcome {
        val a = activity ?: return ApprovalOutcome.Unavailable
        val result = withContext(Dispatchers.Main) {
            BiometricGate.authenticate(a, a.getString(R.string.link_confirm_title), a.getString(R.string.link_confirm_subtitle))
        }
        return when (result) {
            BiometricGate.Result.Success -> ApprovalOutcome.Success
            BiometricGate.Result.Cancelled -> ApprovalOutcome.Cancelled
            BiometricGate.Result.Unavailable -> ApprovalOutcome.Unavailable
            BiometricGate.Result.Failed -> ApprovalOutcome.Failed
        }
    }

    private fun signingCertSha256(): ByteArray? = try {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        info.signingInfo?.apkContentsSigners?.firstOrNull()?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
    } catch (_: Exception) {
        null
    }

    /** The link snapshot comes from (or goes into) the signed-in device's data. */
    private inner class LinkSnapshots : SnapshotSource {
        override suspend fun export(): ByteArray = checkNotNull(openSession()) { "not signed in" }.deviceSync.export()
        override suspend fun import(bytes: ByteArray) = checkNotNull(openSession()) { "not signed in" }.deviceSync.import(bytes)
    }

    private class PasskeysBridge(private val inner: org.example.app.auth.Passkeys) : Passkeys {
        override suspend fun create(optionsJson: String) = inner.create(optionsJson)
        override suspend fun get(optionsJson: String) = inner.get(optionsJson)
    }
}
