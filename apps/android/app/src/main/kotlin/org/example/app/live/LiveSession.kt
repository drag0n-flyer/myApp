package org.example.app.live

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.time.Instant
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import org.example.app.calls.android.CallController
import org.example.app.calls.android.CallHistory
import org.example.app.calls.android.CallMedia
import org.example.app.calls.android.CallPeer
import org.example.app.calls.android.CallPeers
import org.example.app.calls.android.LiveCallEngine
import org.example.app.calls.session.CallContext
import org.example.app.calls.session.IceServer
import org.example.app.calls.session.LiveCallSignaling
import org.example.app.contracts.LiveSocket
import org.example.app.contracts.ServerEvent
import org.example.app.core.chats.Contact
import org.example.app.core.settings.UserSettings
import org.example.app.net.api.TurnApi
import org.example.app.push.MissedCallRecorder
import org.example.app.contracts.Hpke
import org.example.app.contracts.SecretNames
import org.example.app.contracts.SecretStore
import org.example.app.contracts.ServerException
import org.example.app.core.settings.MediaKind
import org.example.app.media.MediaCache
import org.example.app.media.PhotoPrep
import org.example.app.messaging.ChatCallSealer
import org.example.app.messaging.FoundUser
import org.example.app.messaging.LiveChatStore
import org.example.app.messaging.MediaDirs
import org.example.app.messaging.MediaPreparer
import org.example.app.messaging.NotificationProcessor
import org.example.app.messaging.PreparedMedia
import org.example.app.messaging.ReportedLine
import org.example.app.messaging.SocialPort
import org.example.app.messaging.crypto.Escrow
import org.example.app.messaging.crypto.EscrowKeySource
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.MlsLock
import org.example.app.messaging.sync.ConversationManager
import org.example.app.messaging.sync.DeviceNotifier
import org.example.app.messaging.sync.DeviceSync
import org.example.app.messaging.sync.InboxProcessor
import org.example.app.messaging.sync.IncomingHandler
import org.example.app.messaging.sync.KeyPackagePool
import org.example.app.messaging.sync.LocalDevice
import org.example.app.messaging.sync.Membership
import org.example.app.messaging.sync.OwnDeviceNotices
import org.example.app.mls.LiveMlsEngine
import org.example.app.mls.MlsGate
import org.example.app.net.ServerClient
import org.example.app.net.api.DeliveryApiImpl
import org.example.app.net.api.ServerInfoApi
import org.example.app.net.api.SocialApi
import org.example.app.net.models.ReportedMessage
import org.example.app.store.DbKey
import org.example.app.store.RoomRepository
import org.example.app.store.TextFold

private const val TAG = "LiveSession"

/** The server's default `limits.attachmentBytes` (server/internal/config). TODO(I1): read it from `/v1/server`. */
private const val DEFAULT_ATTACHMENT_BYTES = 104_857_600L

/** `MlsGate` as the `:messaging` lock (see `MlsLock`). */
private object GateLock : MlsLock {
    override suspend fun <T> withLock(block: suspend () -> T): T = MlsGate.withLock(block)
}

/**
 * The signed-in services of one device, wired like `TestDevice` in the `:messaging` tests: one database, one MLS
 * engine, one lock. Built when the account is signed in; dropped on sign-out or wipe. Never logs text, names or keys.
 */
class LiveSession(
    val device: LocalDevice,
    val client: ServerClient,
    private val secrets: SecretStore,
    private val platform: LivePlatform,
    context: Context,
    hpke: Hpke,
    /** This session's own scope: cancelled when the session ends, so none of its work outlives the account. */
    val scope: CoroutineScope,
    readReceiptsEnabled: () -> Boolean,
    /** For calls: where this device stands (server policy, settings, network) for a contact or a stranger. */
    callContext: (isContact: Boolean) -> CallContext,
    callController: CallController,
    callSettings: () -> UserSettings,
) {
    private val clock = Clock.System
    val database = platform.openDatabase(platform.databaseName, DbKey.loadOrCreate(secrets))
    val repo = RoomRepository(database, allowLikeFallback = platform.likeFallback)
    val mls = platform.mls(device.identity, platform.mlsDir, secrets)
    val api = DeliveryApiImpl(client)
    private val membership: Membership = Membership(device, mls, realMemberKeys = (mls as? LiveMlsEngine)?.providesRealMemberKeys ?: false, clock = clock)
    private val handler: IncomingHandler = IncomingHandler(device, clock, fold = TextFold::fold, log = { Log.w(TAG, it) })
    private val escrowKeys = EscrowKeySource { ServerInfoApi(client).serverInfo().encryption.escrowKey }
    private val escrow = Escrow.sealer(device.accountId, hpke, escrowKeys)
    private lateinit var inboxRef: InboxProcessor
    private val conversations: ConversationManager = ConversationManager(device, mls, api, repo, GateLock, clock, membership, escrow, syncOnce = { inboxRef.sync() })
    val inbox: InboxProcessor = InboxProcessor(
        mls, api, repo, GateLock, clock, conversations, handler, membership,
        OwnDeviceNotices(repo, api, DeviceNotifier { id, _ -> Log.i(TAG, "own device added: $id") }),
    ).also { inboxRef = it }
    private val sealer: ChatCallSealer = ChatCallSealer(mls, repo, GateLock, clock, conversations, handler)

    /** Own-device reconcile, linking snapshots and the local wipe. */
    val deviceSync = DeviceSync(
        device, mls, api, repo, GateLock, clock, conversations, KeyPackagePool(mls, api, repo, GateLock, clock),
        wipeMls = { if (platform.mlsDir.exists() && !platform.mlsDir.deleteRecursively()) error("mls not deleted") },
        deleteSigningKey = { secrets.delete(SecretNames.MLS_SIGNING) },
        mediaDirectory = platform.mediaDir,
    )

    val chatStore = LiveChatStore(
        device = device, mls = mls, server = api, repo = repo, lock = GateLock, clock = clock, conversations = conversations,
        inbox = inbox, membership = membership, sealer = sealer, escrow = escrow, readReceiptsEnabled = readReceiptsEnabled,
        maxAttachmentBytes = DEFAULT_ATTACHMENT_BYTES,
        dirs = MediaDirs(File(platform.mediaDir, "blobs"), File(platform.cacheDir, "open"), File(platform.cacheDir, "outgoing")),
        social = SocialAdapter(SocialApi(client)), scope = scope, fold = TextFold::fold,
        mediaPreparer = Preparer(context, File(platform.cacheDir, "outgoing")),
        mediaSweep = { MediaCache.sweep(context) },
        onEscrowRejected = { escrowKeys.invalidate() },
    )

    /** What a push shows (D7). */
    val notifications = NotificationProcessor(device, mls, repo, clock, handler, membership, sealer, inbox)

    /** The live socket for this session's host. */
    val socket: SocketHandle = platform.socket(client)

    /** The socket's call frames. The socket has one collector (the graph's watcher), which passes them on here. */
    val callEvents = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)

    /** Calls (E6): the call frames over the socket, signals sealed in the 1:1 chat, the phone through Telecom. */
    val calls: LiveCallEngine = LiveCallEngine(
        scope = scope,
        signaling = LiveCallSignaling(
            object : LiveSocket by socket.socket {
                override val events = callEvents
            },
        ) { TurnApi(client).turnCredentials().iceServers.map { IceServer(it.urls, it.username, it.credential) } },
        sealer = sealer,
        // TODO(E5) is in CallMedia: until WebRtcMediaSession lands, every call fails cleanly at setup.
        media = CallMedia.factory(context, callSettings),
        peers = CallPeers { id ->
            repo.contact(id)?.let { CallPeer(Contact(it.accountId, it.displayName, it.username), it.isContact, repo.isBlocked(id)) }
        },
        context = callContext,
        controller = callController,
        history = CallHistory { summary, contactId, outgoing -> chatStore.recordCall(summary, contactId, outgoing, java.time.Instant.now()) },
        strangers = object : MissedCallRecorder {
            override suspend fun recordMissedCall(callId: String, fromAccountId: String) =
                chatStore.recordStrangerMissedCall(callId, fromAccountId)
        },
        clock = org.example.app.calls.session.Clock { SystemClock.elapsedRealtime() },
    ).also { it.attach() }

    /** Start-up: KeyPackages topped up (others can add this device), leftovers swept, the inbox read, cut-short creates finished. */
    suspend fun start() {
        try {
            deviceSync.replenishKeyPackages()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "key packages not topped up: ${e.javaClass.simpleName}")
        }
        chatStore.sweepStartup()
        chatStore.syncInbox()
        chatStore.resumePendingCreates()
    }

    /** Stops the store and closes the database; the files stay. */
    suspend fun close() {
        socket.stop()
        chatStore.shutdown()
        database.close()
    }

    private class SocialAdapter(private val api: SocialApi) : SocialPort {
        override suspend fun lookUpUser(username: String): FoundUser? = try {
            api.lookUpUser(username).let { FoundUser(it.accountID, it.username, it.displayName) }
        } catch (e: ServerException) {
            if (e.status == 404) null else throw e
        }

        override suspend fun block(accountId: String) = api.block(accountId)

        override suspend fun report(accountId: String, conversationId: String?, messages: List<ReportedLine>) =
            api.report(accountId, conversationId, "other", messages.map { ReportedMessage(Instant.ofEpochMilli(it.sentAt), it.text) }, null)
    }

    /** Photos are scaled and get a thumbnail; files go as they are. TODO(I1): voice-note duration from `VoiceRecorder`. */
    private class Preparer(private val context: Context, private val outDir: File) : MediaPreparer {
        override suspend fun prepare(path: String, kind: MediaKind): PreparedMedia? = when (kind) {
            MediaKind.Photo -> try {
                val uri = if (path.startsWith("content:") || path.startsWith("file:")) Uri.parse(path) else Uri.fromFile(File(path))
                PhotoPrep(context, outDir.apply { mkdirs() }).prepare(uri).let { PreparedMedia(it.file, it.width, it.height, it.thumbnail) }
            } catch (e: Exception) {
                Log.w(TAG, "photo not prepared: ${e.javaClass.simpleName}")
                null
            }
            MediaKind.File, MediaKind.Voice -> PreparedMedia(File(path))
            MediaKind.Video -> null
        }
    }
}
