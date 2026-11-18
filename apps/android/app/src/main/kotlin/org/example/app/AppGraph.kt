package org.example.app

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.app.contracts.ConnectionState
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
import org.example.app.core.account.AccountError
import org.example.app.core.account.AccountState
import org.example.app.core.account.ConnectionStatus
import org.example.app.core.account.SampleAccountService
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.calls.SimulatedCallEngine
import org.example.app.core.calls.CallEngine
import org.example.app.contracts.RingOutcome
import org.example.app.calls.android.CallsRuntime
import org.example.app.calls.android.telecom.FallbackCallController
import org.example.app.calls.android.telecom.PlainCallController
import org.example.app.calls.android.telecom.TelecomCallController
import org.example.app.calls.session.CallContext
import org.example.app.calls.session.MediaSessionFactory
import org.example.app.contracts.CallSignalSealer
import org.example.app.calls.android.CallMedia
import org.example.app.live.SessionCallEngine
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import org.example.app.core.settings.UserSettings
import org.example.app.live.EmptyChatStore
import org.example.app.live.LivePlatform
import org.example.app.live.LocalData
import org.example.app.live.WipeFirst
import org.example.app.live.LiveSession
import org.example.app.ui.media.AttachmentOpener
import org.example.app.ui.media.asOpenedMedia
import org.example.app.messaging.sync.DeviceSync
import org.example.app.messaging.sync.LocalDevice
import org.example.app.messaging.sync.OwnDeviceWatcher
import org.example.app.net.ServerClient
import org.example.app.contracts.ServerException
import org.example.app.net.api.IdentityApi
import org.example.app.net.api.SocialApi
import org.example.app.net.SessionStore
import org.example.app.net.SignOutReason
import org.example.app.net.account.ApprovalConfirmer
import org.example.app.net.account.ApprovalOutcome
import org.example.app.net.account.LiveAccountService
import org.example.app.net.account.PasskeySupportCheck
import org.example.app.net.account.Passkeys
import org.example.app.push.FirebaseInit
import org.example.app.push.PlayServices
import org.example.app.push.PushTransport
import org.example.app.push.UnifiedPushSetup
import org.example.app.ui.lock.AppLock
import org.example.app.ui.settings.SettingsStore

private const val TAG = "AppGraph"
private const val SHUTDOWN_MS = 10_000L
/**
 * The status from the socket state, whether the session is gone and the network. A replaced or revoked socket, or a
 * session that ended, will not reconnect by itself: that is Offline, not "Connecting…" forever.
 */
internal fun statusOf(socket: ConnectionState?, sessionGone: Boolean, networkUp: Boolean): ConnectionStatus = when {
    sessionGone || socket == ConnectionState.Replaced || socket == ConnectionState.Revoked -> ConnectionStatus.Offline
    else -> ConnectionStatus.from(socketConnected = socket == ConnectionState.Connected, hasNetwork = networkUp)
}

private const val SETTINGS_PREFS = "settings"

/** Sample data only. */
private val SAMPLE_POLICY = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")

/**
 * Manual wiring of the services the screens use (plan Task I1). [sampleData] selects the sample services (UI tests,
 * `-sampleData`); otherwise the live ones over the Go server: accounts (`LiveAccountService`), and once signed in a
 * [LiveSession] with the MLS-backed chat store, the live socket and push. Nothing in the live build reads a sample
 * value: the server policy, network and settings come from the server and the device.
 *
 * Calls run on the [SimulatedCallEngine] with sample data and on the session's `LiveCallEngine` live. The live socket
 * is open only while the app is visible or a call is active ([setVisible], [setInCall]); push wakes the app otherwise.
 */
class AppGraph(
    context: Context,
    val sampleData: Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val platform: LivePlatform = LivePlatform.android(context),
    /** Telecom first, a plain notification and service where Telecom refuses. Tests swap the phone side. */
    private val makeCallController: (Context) -> FallbackCallController =
        { FallbackCallController(TelecomCallController(it), PlainCallController(it)) },
    /** The peer connection for each call; `CallMedia` holds the TODO(E5) seam. */
    private val callMedia: (Context, () -> UserSettings) -> MediaSessionFactory = CallMedia::factory,
    /** Null: the session's MLS call sealer. Tests that run a call past the ring set a plain one. */
    private val callSealer: CallSignalSealer? = null,
) {
    private val appContext = context.applicationContext

    /** The server's policy: the sample one, or the connected server's (its host is the session's). */
    val serverPolicy: ServerPolicy
        get() = if (sampleData) SAMPLE_POLICY else liveAccount.currentServer
            ?: sessions.current?.let { liveAccount.defaultServer.copy(host = it.host) }
            ?: liveAccount.defaultServer

    /** The single source of truth for the user's settings; consumers read it as a flow. */
    val settingsStore: SettingsStore by lazy {
        // Live settings are kept on the device and survive a restart; sample data keeps them in memory.
        if (sampleData) SettingsStore()
        else SettingsStore(appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE), UserSettings.initial(serverPolicy))
    }

    /** The app lock's state for the process; starts locked when the setting is on (see [AppLock]). */
    val appLock: AppLock by lazy { AppLock({ settingsStore.settings.value }) }

    /** Whether app lock is on for this account: the one place other code (notifications) reads. */
    val appLockOn: Boolean get() = settingsStore.settings.value.appLock

    /** The network the device is on now (always Wi-Fi with sample data). */
    val network: NetworkType get() = if (sampleData) NetworkType.Wifi else platform.network()

    /** A chat a notification tap asked to open; `AppRoot` opens it once the chats show, then clears it. */
    val requestedChat = MutableStateFlow<String?>(null)

    // ---- Connection status

    private val networkUp = MutableStateFlow(sampleData || platform.network() != NetworkType.Offline)
    /** The current session's socket state; null while no session has opened. */
    private val socketState = MutableStateFlow<ConnectionState?>(null)
    /** True once the session that was open has ended (sign-out, revocation): nothing will reconnect. */
    private val sessionGone = MutableStateFlow(false)
    /** The system's current default network (any object: the callbacks' `Network`, or a test's stand-in). */
    private var defaultNetwork: Any? = null

    /**
     * What the chat list strip and Settings > Server show: the live socket and the device's connectivity together,
     * derived from both flows so a stale write cannot win ([statusOf]). Sample data is always connected.
     */
    val connectionStatus: StateFlow<ConnectionStatus> =
        if (sampleData) MutableStateFlow(ConnectionStatus.Connected)
        else combine(socketState, sessionGone, networkUp, ::statusOf)
            .stateIn(scope, SharingStarted.Eagerly, statusOf(null, false, networkUp.value))

    /** The system reports a default network. Also the test seam. */
    @Synchronized internal fun onNetworkAvailable(network: Any) {
        defaultNetwork = network
        networkUp.value = true
    }

    /** A network went away; only the current default's loss counts (Wi-Fi to mobile reports the old one lost after the new is up). */
    @Synchronized internal fun onNetworkLost(network: Any) {
        if (network != defaultNetwork) return
        defaultNetwork = null
        networkUp.value = false
    }

    private fun watchConnectivity() {
        if (sampleData) return
        try {
            appContext.getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) = onNetworkAvailable(network)
                    override fun onLost(network: android.net.Network) = onNetworkLost(network)
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "connectivity callback failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * How push reaches this phone, for the read-only row in Settings > Notifications. The same rule as the
     * registration (`PushTransport.choose`), but Firebase is only checked for a complete configuration, never
     * initialised here. The registered transport is not recorded anywhere, so this is the rule's answer.
     */
    fun pushTransport(): PushTransport {
        if (sampleData) return PushTransport.None
        return try {
            val up = UnifiedPushSetup(appContext)
            PushTransport.choose(
                playServices = PlayServices.available(appContext), distributorCount = up.distributors().size,
                distributorChosen = up.distributorChosen(), fcmConfigured = FirebaseInit.buildConfigOptions().complete,
            )
        } catch (_: Exception) {
            PushTransport.None
        }
    }

    /** Calls show in both builds: the simulated engine with sample data, `LiveCallEngine` live. */
    val callsAvailable: Boolean get() = true

    /** The invite link on the Contacts tab; none in the live build until the app can make real invites. */
    val inviteUrl: String? get() = if (sampleData) "https://${SAMPLE_POLICY.host}/invite/${SampleAccountService.SAMPLE_INVITE_CODE}" else null

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
        scope: CoroutineScope = this.scope,
    ) = OwnDeviceWatcher(
        scope = scope,
        syncInbox = syncInbox,
        reconcile = deviceSync::reconcileOwnDevices,
        handleRevoked = deviceSync::handleRevoked,
        refreshDevices = { deviceListChanged.emit(Unit) },
        wipe = wipe,
        log = { Log.w("OwnDevices", it) },
    ).start(socket.states, socket.events)

    /**
     * The simulated engine with sample data. Live, a [SessionCallEngine] that forwards to the open session's
     * `LiveCallEngine` (E6); the Telecom service and the call activities reach it through [CallsRuntime].
     */
    val callEngine: CallEngine by lazy {
        val engine: CallEngine = if (sampleData) {
            SimulatedCallEngine(server = serverPolicy, settings = settingsStore.settings.value.call, network = network).also { sim ->
                settingsStore.settings.map { it.call }.distinctUntilChanged().onEach { sim.settings = it }.launchIn(scope)
            }
        } else {
            SessionCallEngine(scope).also {
                CallsRuntime.controller = callController
                CallsRuntime.engine = it
            }
        }
        engine.state.map { it != null && !it.isEnded }.distinctUntilChanged().onEach { setInCall(it) }.launchIn(scope)
        if (!sampleData) watchNetworkDuringCalls(engine)
        engine
    }

    /** Live calls hear about Wi-Fi / mobile data changes at once: watched only while a call is on (NetworkWatcher dedupes repeats). */
    private fun watchNetworkDuringCalls(engine: CallEngine) {
        val connectivity = appContext.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val watcher = org.example.app.calls.android.NetworkWatcher(connectivity) { engine.setNetwork(it) }
        engine.state.map { it != null && !it.isEnded }.distinctUntilChanged().onEach { live ->
            try {
                if (live) watcher.start() else watcher.stop()
            } catch (e: RuntimeException) {
                Log.w(TAG, "network watcher: ${e.javaClass.simpleName}")
            }
        }.launchIn(scope)
    }

    private val callController: FallbackCallController by lazy { makeCallController(appContext) }

    private fun callContext(isContact: Boolean) =
        CallContext(serverPolicy, settingsStore.settings.value, network, isContact)

    /** The activity on screen, for the passkey and biometric prompts. Set by [MainActivity]. */
    @Volatile var activity: FragmentActivity? = null

    // ---- Accounts

    private val secrets by lazy { platform.secrets(platform.secretsDir) }
    private val sessions by lazy { SessionStore(secrets) }
    private val local = LocalData(appContext, platform) { secrets }

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
        ).also { account ->
            account.onSignedOut = { reason ->
                scope.launch {
                    // Ruling: only the server's proof (a 4003 close, a confirmed 401) wipes; anything else signs out.
                    if (reason == SignOutReason.Revoked) quietly("wipe") { wipeAfterRevocation() } else endSession()
                }
            }
        }
    }

    private val guardedAccount: AccountService by lazy { WipeFirst(liveAccount) { finishPendingWipe(); accountActions.clearError() /* a sign-in starts clean */ } }
    private val sampleAccount by lazy { SampleAccountService() }
    private val sampleChats by lazy { SampleChatStore() }

    /** Decrypts a downloaded attachment for the chat screen. Nothing with sample data or while signed out. */
    val attachmentOpener = AttachmentOpener { messageId ->
        val session = liveSession ?: return@AttachmentOpener null
        // Runs in the session's scope, so the sign-out wipe waits for it. A caller that gave up closes what it was about to get.
        val job = session.scope.async { session.chatStore.openAttachment(messageId)?.asOpenedMedia() }
        try {
            job.await()
        } catch (e: CancellationException) {
            job.invokeOnCompletion { if (it == null) runCatching { job.getCompleted()?.close() } }
            if (currentCoroutineContext().isActive) null else throw e
        }
    }

    /** Sample accounts, or the live ones; a live sign-in first finishes any pending wipe. */
    val accountService: AccountService get() = if (sampleData) sampleAccount else guardedAccount

    /** What the app shows first. A pending full wipe counts as signed out: nothing is opened over it. */
    val accountState: AccountState
        get() = when {
            sampleData -> AccountState.SignedIn
            local.pending == LocalData.FULL -> AccountState.SignedOut
            else -> accountService.accountState
        }

    // ---- Signed-in services

    /** The signed-in services; null while signed out. */
    @Volatile var liveSession: LiveSession? = null
        private set

    private val _sessionEnds = MutableStateFlow(0)
    /** Bumped when the session ended (server sign-out, revocation wipe): the UI goes back to onboarding. */
    val sessionEnds: StateFlow<Int> = _sessionEnds.asStateFlow()

    /**
     * Sample data, or the open session's chats. Never opens anything and never throws: with no open session (it is still
     * opening, or it just ended and onboarding is on its way) it is an empty store.
     */
    val chatStore: ChatStore
        get() = if (sampleData) sampleChats else liveSession?.chatStore ?: EmptyChatStore

    @Volatile private var wipeRunning = false

    /** Counts revocation wipes (guarded by `this`): an open that saw a stored session aborts if one started since. */
    private var revocations = 0

    /**
     * Opens the signed-in services off the main thread if the account is signed in; null otherwise, and always null
     * while a wipe runs or is pending (a pending wipe is finished first). Data of another account is wiped first.
     */
    /** A contact's call that came by push (D7 checked it): the session's `LiveCallEngine` rings it. */
    fun ring(outcome: RingOutcome.Ring) {
        callEngine // the Telecom service and the call screens reach the engine through CallsRuntime
        scope.launch { quietly("ring") { openSession()?.calls?.ring(outcome) } }
    }

    suspend fun openSession(): LiveSession? = withContext(Dispatchers.IO) {
        if (sampleData) return@withContext null
        if (!wipeRunning) liveSession?.let { return@withContext it }
        // One open at a time (UI and push may race): ownership is checked, and another device's data wiped, only here,
        // so a second caller never wipes the session the first one just opened. `wipeRunning` is read under the lock
        // only: the open's own wipe of another device's data sets it, and a caller arriving then waits for that open
        // and gets its session instead of null.
        opening.withLock { openLocked() }
    }

    private val opening = Mutex()

    /** Test seam only: runs after an open read the stored session and before it takes the lock. */
    internal var beforeOpenLock: suspend () -> Unit = {}

    /** Test seam only: runs after an open checked for a pending wipe and before it took the revocation count. */
    internal var beforeRevocationSnapshot: suspend () -> Unit = {}

    private suspend fun openLocked(): LiveSession? {
        if (wipeRunning) return null
        liveSession?.let { return it }
        if (local.pending != null) quietly("pending wipe") { finishPendingWipe() }
        if (local.pending != null) return null
        beforeRevocationSnapshot()
        // Taken with the running check: a revocation wipe that starts and ends before the lock below is still seen.
        val revoked = synchronized(this@AppGraph) { if (wipeRunning) return null; revocations }
        // A wipe that ran and failed between the check above and the count leaves only its marker: nothing opens on it.
        if (local.pending != null) return null
        val stored = sessions.current ?: return null
        val device = LocalDevice(stored.accountId, stored.deviceId)
        if (!local.ownedBy(device.identity)) {
            // Another account's data goes, and so do its settings (first: a kill leaves the owner mismatch to try again).
            quietly("other account's settings") { settingsStore.reset(UserSettings.initial(serverPolicy)) }
            quietly("other account's data") { wipe(LocalData.DATA) }
            if (local.pending != null) return null
        }
        val client = liveAccount.sessionClient ?: return null
        beforeOpenLock()
        return synchronized(this@AppGraph) {
            if (wipeRunning || revocations != revoked) return null
            liveSession?.let { return it }
            local.recordOwner(device.identity)
            accountActions.clearError() // a failure from the last account's sign-out must not show for this one
            val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
            val session = LiveSession(
                device, client, secrets, platform, appContext, hpke, sessionScope,
                readReceiptsEnabled = { settingsStore.settings.value.readReceipts },
                callContext = ::callContext,
                callController = callController,
                callSettings = { settingsStore.settings.value },
                callMedia = callMedia,
                callSealer = callSealer,
            )
            liveSession = session
            (callEngine as? SessionCallEngine)?.use(session.calls)
            start(session)
            session
        }
    }

    private fun start(session: LiveSession) {
        session.scope.launch {
            quietly("start") { session.start() }
            if (seedNameOnOpen) quietly("own name") { seedOwnName(session) }
            quietly("push") { org.example.app.live.PushWiring.registerAfterSignIn(appContext, session.client) }
        }
        session.scope.launch { quietly("read chats") { org.example.app.live.PushWiring.cancelReadChats(appContext, session.chatStore) } }
        val live = session.socket.socket
        sessionGone.value = false
        session.scope.launch { live.states.collect { socketState.value = it } }
        // The socket's events have one collector: the watcher. A pushed inbox item is read on the way through.
        // Call frames go on to the session's `LiveCallEngine`.
        val events = live.events.onEach {
            when (it) {
                is ServerEvent.Inbox -> quietly("sync") { session.chatStore.syncInbox() }
                is ServerEvent.CallRing, is ServerEvent.CallAnswered, is ServerEvent.CallSignal, is ServerEvent.CallEnded ->
                    session.callEvents.emit(it)
                is ServerEvent.DeviceRevoked -> Unit
            }
        }
        val socket = object : LiveSocket by live {
            override val events = events
        }
        watchOwnDevices(
            socket, session.deviceSync, syncInbox = { session.chatStore.syncInbox() },
            // In the graph's scope: the wipe cancels the session's scope, which must not cancel the wipe.
            wipe = { scope.launch { quietly("wipe") { wipeAfterRevocation() } }.join() },
            scope = session.scope,
        )
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

    /** Takes the open session out of use: no new work starts on it. */
    private fun detachSession(): LiveSession? = synchronized(this) {
        liveSession.also {
            liveSession = null
            if (it != null) (callEngine as? SessionCallEngine)?.use(null)
        }.also {
            socketState.value = null
            if (it != null) sessionGone.value = true
        }
    }

    /** Stops and closes [session] step by step; every step runs. */
    private suspend fun stop(session: LiveSession, step: suspend (suspend () -> Unit) -> Unit, wipeRows: Boolean) {
        // First, while the socket can still carry the hang-up: no call (Telecom, ringtone, notification, service)
        // outlives the session into the next account.
        step { session.calls.shutdown() }
        step { session.socket.stop() }
        step { withTimeoutOrNull(SHUTDOWN_MS) { session.chatStore.shutdown() } }
        // Wait for the session's jobs to end: a download or upload in a blocking write must not re-create a folder the wipe
        // is about to delete.
        step { withTimeoutOrNull(SHUTDOWN_MS) { session.scope.coroutineContext.job.cancelAndJoin() } }
        if (wipeRows) step { session.deviceSync.wipeLocalData() }
        step { session.database.close() }
    }

    /** The server ended the session without proof of revocation: signed out, the data stays for the same account. */
    private suspend fun endSession() {
        detachSession()?.let { stop(it, { body -> quietly("end") { body() } }, wipeRows = false) }
        _sessionEnds.value += 1
    }

    private val wiping = Mutex()

    /**
     * Signs out of this device: removes it from the account on the server (the revoke call Linked devices uses), then
     * wipes everything local and returns to onboarding. Throws [AccountError] (`Network` with no connection) and
     * wipes nothing when the server was not reached, so the person can try again. Sample data just goes back.
     */
    suspend fun signOut() {
        if (sampleData) { _sessionEnds.value += 1; return }
        // In the graph's scope: leaving the screen or a rotation must not stop it between the revoke and the wipe.
        scope.async {
            val own = accountService.devices().firstOrNull { it.isThisDevice }
            // A device the server does not list is already gone from the account: nothing to revoke, the wipe is all that is left.
            own?.let { accountService.removeDevice(it.id) }
            wipeAfterRevocation()
        }.await()
    }

    /** Sign-out and delete, one at a time for the whole app, run in this graph's scope (the profile screen only shows them). */
    val accountActions: org.example.app.ui.settings.AccountActions by lazy {
        org.example.app.ui.settings.AccountActions(scope, signOut = ::signOut, deleteAccount = ::deleteAccount, saveName = ::saveDisplayName)
    }

    /**
     * Changes the person's own display name (`PATCH /v1/me/settings`, the call "find me by email" uses). The new name
     * shows at once; if the server did not take it the old one comes back and this throws [AccountError]. It runs in the
     * graph's scope, so leaving the screen mid-save still ends with the two matching. A name [DisplayNameRule] refuses
     * never reaches the server. [AccountActions] lets only one save run at a time. A save that finishes after a
     * sign-out or a different account's session (the settings generation moved on, or the store is another one) writes
     * nothing: the name belongs to the account that has gone.
     */
    suspend fun saveDisplayName(name: String) {
        val clean = org.example.app.core.profile.DisplayNameRule.clean(name) ?: throw AccountError.Server
        if (sampleData) { chatStore.setMyDisplayName(clean); return }
        val generation = settingsStore.generation
        val store = chatStore
        scope.async {
            fun current() = settingsStore.generation == generation && chatStore === store
            val previous = store.myStoredName // "" when none is known: never the fallback "You"
            if (current()) store.setMyDisplayName(clean)
            try {
                val client = openSession()?.client ?: throw AccountError.Server
                SocialApi(client).updateSettings(clean, null)
            } catch (e: Exception) {
                if (current()) store.setMyDisplayName(previous)
                if (e is CancellationException) throw e
                throw when {
                    e is AccountError -> e
                    e is ServerException && e.code == "network" -> AccountError.Network
                    else -> AccountError.Server
                }
            }
        }.await()
    }

    /** Reads the person's name from the server (`GET /v1/me`) into an own contact row that has none. */
    /** Tests turn this off to drive [seedOwnName] themselves. */
    internal var seedNameOnOpen = true

    suspend fun seedOwnName(session: org.example.app.live.LiveSession) {
        val store = session.chatStore
        if (store.myStoredName.isNotEmpty()) return
        val generation = settingsStore.generation
        val name = try {
            IdentityApi(session.client).me().displayName
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return // silent: the next open tries again
        }
        val clean = org.example.app.core.profile.DisplayNameRule.forDisplay(name, "")
        // Same guard as a save: a sign-out or another session while the read was out means this name is not ours to store.
        if (clean.isEmpty() || settingsStore.generation != generation || liveSession !== session || chatStore !== store) return
        if (store.myStoredName.isEmpty()) store.setMyDisplayName(clean)
    }

    private val findByEmailLock = Mutex()

    /**
     * Sets "find me by email" (`PATCH /v1/me/settings`) and keeps the local value matching the server. The local value
     * changes first; if the server did not take it the value goes back to what it was when this save's turn came and this throws [AccountError] for the screen to
     * show. It runs in the graph's scope, so leaving the screen mid-save still ends with the two matching. Saves run
     * one at a time. Sample data has no server.
     */
    suspend fun saveFindByEmail(value: Boolean) {
        if (sampleData) { settingsStore.update { it.copy(findByEmail = value) }; return }
        // Read before anything waits: a sign-out while this is queued or in flight makes both updates below no-ops.
        val generation = settingsStore.generation
        scope.async {
            findByEmailLock.withLock {
                // The value the server has: the last save that finished (a failed one went back), read when it is our turn.
                val previous = settingsStore.settings.value.findByEmail
                settingsStore.update(generation) { it.copy(findByEmail = value) }
                try {
                    val client = openSession()?.client ?: throw AccountError.Server
                    SocialApi(client).updateSettings(null, value)
                } catch (e: Exception) {
                    settingsStore.update(generation) { it.copy(findByEmail = previous) }
                    if (e is CancellationException) throw e
                    throw when {
                        e is AccountError -> e
                        e is ServerException && e.code == "network" -> AccountError.Network
                        else -> AccountError.Server
                    }
                }
            }
        }.await()
    }

    /** The server's "find me by email", or null when it cannot be read now (offline, sample data). */
    suspend fun loadFindByEmail(): Boolean? {
        if (sampleData) return null
        return try {
            IdentityApi(openSession()?.client ?: return null).me().findByEmail
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Deletes the account on the server, then wipes everything local. Same failure rule as [signOut]. */
    suspend fun deleteAccount() {
        if (sampleData) { _sessionEnds.value += 1; return }
        scope.async {
            accountService.deleteAccount()
            wipeAfterRevocation()
        }.await()
    }

    /** The chat to open for a notification tap: [id] if this account has that chat (or request), else null. */
    fun openableChat(id: String?): String? = id?.takeIf { it.isNotBlank() && chatStore.chat(it) != null }

    /**
     * This device was revoked (a 4003 close or a confirmed 401; never `device.revoked` alone): signs out, then deletes
     * the database, `mls/`, `media/`, the decrypted and outgoing caches, the posted notifications, every secret in
     * `secrets/` and the Keystore key. Crash-safe: see [LocalData]. Every step runs; the first failure is thrown.
     */
    suspend fun wipeAfterRevocation() = wipe(LocalData.FULL)

    /** Finishes a wipe a kill or a failure left (the app start and every sign-in call this first). */
    suspend fun finishPendingWipe() {
        local.pending?.let { wipe(it) }
    }

    private suspend fun wipe(kind: String) = withContext(NonCancellable + Dispatchers.IO) {
        wiping.withLock {
            synchronized(this@AppGraph) {
                wipeRunning = true
                if (kind == LocalData.FULL) revocations++ // the open's own wipe of another device's data is not one
            }
            var failure: Exception? = null
            val step: suspend (suspend () -> Unit) -> Unit = { body ->
                try { body() } catch (e: Exception) { failure = failure ?: e; Log.w(TAG, "wipe step failed: ${e.javaClass.simpleName}") }
            }
            try {
                step { local.markWipePending(kind) } // durable first
                if (kind == LocalData.FULL) step { liveAccount.signOutLocally() } // no session can open from here on
                detachSession()?.let { stop(it, step, wipeRows = true) }
                // The next account starts from its own defaults, not this one's settings. Before the delete: the delete
                // removes the marker last, so a kill after it must not leave the old settings behind.
                if (kind == LocalData.FULL) step { settingsStore.reset(UserSettings.initial(serverPolicy)) }
                local.deleteAll(kind)?.let { failure = failure ?: it }
            } finally {
                synchronized(this@AppGraph) { wipeRunning = false }
                if (kind == LocalData.FULL) _sessionEnds.value += 1
            }
            failure?.let { throw it }
            Unit
        }
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

    init {
        watchConnectivity()
    }
}
