package org.example.app.interop

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import org.example.app.calls.session.Fingerprint
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.MemorySecretStore
import org.example.app.contracts.SnapshotSource
import org.example.app.core.account.ServerAddress
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.settings.MediaKind
import org.example.app.messaging.ChatCallSealer
import org.example.app.messaging.FoundUser
import org.example.app.messaging.LiveChatStore
import org.example.app.messaging.MediaDirs
import org.example.app.messaging.MediaPreparer
import org.example.app.messaging.PreparedMedia
import org.example.app.messaging.ReportedLine
import org.example.app.messaging.SocialPort
import org.example.app.messaging.crypto.Escrow
import org.example.app.messaging.crypto.EscrowKeySource
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.MlsLock
import org.example.app.messaging.sync.ConversationManager
import org.example.app.messaging.sync.DeviceSync
import org.example.app.messaging.sync.InboxProcessor
import org.example.app.messaging.sync.IncomingHandler
import org.example.app.messaging.sync.KeyPackagePool
import org.example.app.messaging.sync.LocalDevice
import org.example.app.messaging.sync.Membership
import org.example.app.messaging.testing.InMemoryRepository
import org.example.app.mls.LiveMlsEngine
import org.example.app.mls.MlsGate
import org.example.app.mls.RustHpke
import org.example.app.net.ServerClient
import org.example.app.net.SessionStore
import org.example.app.net.account.ApprovalConfirmer
import org.example.app.net.account.ApprovalOutcome
import org.example.app.net.account.LiveAccountService
import org.example.app.net.account.Passkeys
import org.example.app.net.api.DeliveryApiImpl
import org.example.app.net.api.ServerInfoApi
import org.example.app.net.api.SocialApi

/** A command failed; the message goes back to the script as `ERR <message>`. */
class PeerError(message: String) : Exception(message)

private object GateLock : MlsLock {
    override suspend fun <T> withLock(block: suspend () -> T): T = MlsGate.withLock(block)
}

/** What the script sets for every peer (scripts/interop.sh). */
class PeerConfig(
    val baseUrl: String,
    val mailDir: File,
    val invite: String,
    val dataDir: File,
    val deviceName: String,
)

/**
 * One Android device, wired like the app's `LiveSession` but on the JVM: the in-memory repository instead of
 * Room, the host Rust MLS engine and HPKE, the inbox read by polling instead of the live socket.
 */
class Peer(private val config: PeerConfig) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hpke = RustHpke()
    private val sessions = SessionStore(MemorySecretStore())
    private val address = ServerAddress.parse(config.baseUrl) ?: throw PeerError("bad base URL ${config.baseUrl}")
    private var graph: Graph? = null

    /** Built once the account exists; the snapshot of a link is imported into it. */
    private val snapshots = object : SnapshotSource {
        override suspend fun export(): ByteArray = signedIn().deviceSync.export()
        override suspend fun import(bytes: ByteArray) = signedIn().deviceSync.import(bytes)
    }

    private val account: LiveAccountService = LiveAccountService(
        client = ServerClient(address, sessions, OkHttpClient()),
        sessionStore = sessions,
        passkeys = object : Passkeys {
            override suspend fun create(optionsJson: String): String = throw PeerError("no passkeys")
            override suspend fun get(optionsJson: String): String = throw PeerError("no passkeys")
        },
        hpke = hpke,
        snapshots = snapshots,
        deviceName = config.deviceName,
        biometrics = ApprovalConfirmer { ApprovalOutcome.Success },
        makeClient = { ServerClient(it, sessions, OkHttpClient()) },
    )

    private inner class Graph {
        val session = sessions.current ?: throw PeerError("not signed in")
        val device = LocalDevice(session.accountId, session.deviceId)
        val client = account.sessionClient ?: throw PeerError("no session client")
        val clock = Clock.System
        val repo = InMemoryRepository()
        val mls = LiveMlsEngine(device.identity, File(config.dataDir, "mls"), MemorySecretStore())
        val api = DeliveryApiImpl(client)
        // Real member keys: the safety number covers the MLS signature keys, as on the iPhone.
        val membership = Membership(device, mls, realMemberKeys = true, clock = clock)
        val handler = IncomingHandler(device, clock)
        val escrowKeys = EscrowKeySource { ServerInfoApi(client).serverInfo().encryption.escrowKey }
        val escrow = Escrow.sealer(device.accountId, hpke, escrowKeys)
        lateinit var inboxRef: InboxProcessor
        val conversations: ConversationManager = ConversationManager(device, mls, api, repo, GateLock, clock, membership, escrow, syncOnce = { inboxRef.sync() })
        val inbox: InboxProcessor = InboxProcessor(mls, api, repo, GateLock, clock, conversations, handler, membership).also { inboxRef = it }
        val sealer = ChatCallSealer(mls, repo, GateLock, clock, conversations, handler)
        val media = File(config.dataDir, "media").apply { mkdirs() }
        val deviceSync = DeviceSync(
            device, mls, api, repo, GateLock, clock, conversations, KeyPackagePool(mls, api, repo, GateLock, clock),
            mediaDirectory = media,
        )
        val store = LiveChatStore(
            device = device, mls = mls, server = api, repo = repo, lock = GateLock, clock = clock,
            conversations = conversations, inbox = inbox, membership = membership, sealer = sealer, escrow = escrow,
            readReceiptsEnabled = { true }, maxAttachmentBytes = 104_857_600L,
            dirs = MediaDirs(File(media, "blobs").apply { mkdirs() }, File(media, "open").apply { mkdirs() }, File(media, "out").apply { mkdirs() }),
            social = Social(SocialApi(client)), scope = scope,
            // The photo goes as it is: the receiver's bytes must equal the fixture's.
            mediaPreparer = MediaPreparer { path, kind ->
                when (kind) {
                    MediaKind.Photo -> PreparedMedia(File(path), PHOTO_WIDTH, PHOTO_HEIGHT)
                    else -> PreparedMedia(File(path))
                }
            },
            onEscrowRejected = { escrowKeys.invalidate() },
        )
    }

    private fun signedIn(): Graph = graph ?: Graph().also { graph = it }

    private val store: LiveChatStore get() = signedIn().store

    private class Social(private val api: SocialApi) : SocialPort {
        override suspend fun lookUpUser(username: String): FoundUser? =
            api.lookUpUser(username).let { FoundUser(it.accountID, it.username, it.displayName) }
        override suspend fun block(accountId: String) = api.block(accountId)
        override suspend fun report(accountId: String, conversationId: String?, messages: List<ReportedLine>) = Unit
    }

    // ---- commands

    suspend fun signup(username: String, email: String, displayName: String): String {
        account.connect(config.baseUrl)
        if (!account.redeemInvite(config.invite)) throw PeerError("invite refused")
        val before = Mailbox.newest(config.mailDir)
        account.startSignUp(displayName, username, email)
        account.verifyEmail(Mailbox.code(config.mailDir, before))
        account.finishSignUpWithEmailCode()
        return start()
    }

    /** Key packages up, so others can add this device; then the first inbox read. */
    private suspend fun start(): String {
        val g = signedIn()
        g.deviceSync.replenishKeyPackages()
        g.store.syncInbox()
        return g.device.accountId
    }

    suspend fun add(username: String): String {
        val contact = store.addContact(username) ?: throw PeerError("no user $username")
        store.ensureChat(contact.id)
        return contact.id
    }

    suspend fun send(peer: String, text: String, replyTo: String? = null): String {
        store.send(MessageContent.Text(text), peer, replyTo)
        settle()
        val sent = messages(peer).lastOrNull { it.senderId == me && (it.content as? MessageContent.Text)?.text == text }
            ?: throw PeerError("message not stored")
        if (sent.status == DeliveryStatus.Sending) throw PeerError("message not sent")
        return sent.id
    }

    suspend fun waitText(peer: String, text: String, replyTo: String? = null): String =
        waitFor("text from $peer") {
            messages(peer).lastOrNull {
                it.senderId == peer && (it.content as? MessageContent.Text)?.text == text && (replyTo == null || it.replyToId == replyTo)
            }?.id
        }

    suspend fun react(peer: String, messageId: String, emoji: String) {
        store.react(emoji, messageId, peer)
        settle()
    }

    suspend fun waitReaction(peer: String, messageId: String, emoji: String) {
        waitFor("reaction") { messages(peer).firstOrNull { it.id == messageId && emoji in it.reactions } }
    }

    suspend fun edit(peer: String, messageId: String, text: String) {
        store.edit(messageId, peer, text)
        settle()
    }

    suspend fun waitEdit(peer: String, messageId: String, text: String) {
        waitFor("edit") {
            messages(peer).firstOrNull { it.id == messageId && it.editedAt != null && (it.content as? MessageContent.Text)?.text == text }
        }
    }

    suspend fun read(peer: String) {
        sync()
        store.markRead(peer)
        settle()
    }

    suspend fun waitStatus(peer: String, messageId: String, wanted: String) {
        val want = when (wanted) {
            "delivered" -> DeliveryStatus.Delivered
            "read" -> DeliveryStatus.Read
            else -> throw PeerError("unknown status $wanted")
        }
        waitFor("status $wanted") { messages(peer).firstOrNull { it.id == messageId && it.status.ordinal >= want.ordinal } }
    }

    /** Answers the message ID and the SHA-256 of the bytes sent (the file as it is). */
    suspend fun sendPhoto(peer: String, path: String): String {
        val before = messages(peer).map { it.id }.toSet()
        store.sendAttachment(path, MediaKind.Photo, peer)
        settle()
        val sent = messages(peer).lastOrNull { it.id !in before && it.senderId == me && it.content is MessageContent.Photo }
            ?: throw PeerError("photo not stored")
        if (sent.status == DeliveryStatus.Sending) throw PeerError("photo not sent")
        return "${sent.id} ${sha256(File(path).readBytes())}"
    }

    /** Downloads the photo message [messageId] and answers the SHA-256 of the decrypted bytes. */
    suspend fun waitPhoto(peer: String, messageId: String): String {
        waitFor("photo") { messages(peer).firstOrNull { it.id == messageId && it.content is MessageContent.Photo } }
        store.download(messageId, peer)
        settle()
        val opened = store.openAttachment(messageId) ?: throw PeerError("photo did not download or decrypt")
        return opened.use { sha256(it.file.readBytes()) }
    }

    fun conversation(peer: String): String =
        signedIn().repo.preferredConversation(peer)?.serverId ?: throw PeerError("no conversation with $peer")

    suspend fun safety(peer: String): String {
        sync()
        return store.safetyNumber(peer)?.groups?.joinToString("") ?: throw PeerError("no safety number")
    }

    suspend fun seal(peer: String, kind: String, callId: String, sdpPath: String?): String {
        val payload = when (kind) {
            "ring" -> CallSignalPayload(CallSignalPayload.Kind.ring, callId, video = false)
            "offer" -> {
                val sdp = File(sdpPath ?: throw PeerError("offer needs an SDP file")).readText()
                CallSignalPayload(CallSignalPayload.Kind.offer, callId, sdp = sdp, dtlsFingerprint = Fingerprint.local(sdp))
            }
            else -> throw PeerError("unknown kind $kind")
        }
        return Base64.getEncoder().encodeToString(store.seal(payload, peer))
    }

    /** Answers `<kind> <callID>`, plus `fingerprint-ok` for an offer whose SDP passes the fingerprint check. */
    suspend fun open(peer: String, sealed: String): String {
        val opened = store.open(Base64.getDecoder().decode(sealed), peer)
        val p = opened.payload
        if (opened.senderDeviceID.isNullOrEmpty()) throw PeerError("no sender device")
        return when (p.kind) {
            CallSignalPayload.Kind.offer ->
                if (Fingerprint.isValid(p.sdp, p.dtlsFingerprint)) "offer ${p.callID} fingerprint-ok" else throw PeerError("fingerprint check failed")
            else -> "${p.kind} ${p.callID}"
        }
    }

    suspend fun linkCode(): String {
        account.connect(config.baseUrl)
        return account.makeLinkCode().qrText
    }

    /** Answers `<accountID> <contact IDs, comma separated>` once linked and the snapshot is imported. */
    suspend fun linkWait(): String {
        val result = account.waitForLinkApproval()
        if (!result.contactsCopied) throw PeerError("snapshot not imported")
        val id = start()
        return "$id ${store.contacts.value.joinToString(",") { it.id }}"
    }

    suspend fun approve(qrText: String) {
        val approval = account.approveLink(account.linkRequest(qrText))
        if (!approval.snapshotSent) throw PeerError("snapshot not sent")
    }

    // ---- helpers

    private val me: String get() = signedIn().device.accountId

    private fun messages(peer: String): List<Message> = store.messages(peer, null, 200)

    private suspend fun settle() {
        store.settle()
        store.refresh()
    }

    private suspend fun sync() {
        store.syncInbox()
        settle()
    }

    /** Reads the inbox until [found] gives a value, for up to 60 s. */
    private suspend fun <T : Any> waitFor(what: String, found: () -> T?): T {
        val deadline = System.currentTimeMillis() + 60_000
        while (true) {
            sync()
            found()?.let { return it }
            if (System.currentTimeMillis() > deadline) throw PeerError("timed out waiting for $what")
            delay(250)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** apps/android/interop/fixtures/photo.jpg */
        const val PHOTO_WIDTH = 64
        const val PHOTO_HEIGHT = 48
    }
}

/** The SMTP sink's directory: `<n>.eml` per message. */
object Mailbox {
    fun newest(dir: File): Int =
        dir.listFiles().orEmpty().mapNotNull { f -> f.name.removeSuffix(".eml").takeIf { f.name.endsWith(".eml") }?.toIntOrNull() }.maxOrNull() ?: 0

    /** The six-digit code in the first message newer than [after], waiting up to 10 s. */
    suspend fun code(dir: File, after: Int): String {
        repeat(100) {
            val n = newest(dir)
            if (n > after) {
                val text = File(dir, "$n.eml").readText()
                return Regex("""is (\d{6})\.""").find(text)?.groupValues?.get(1) ?: throw PeerError("message $n.eml has no code")
            }
            delay(100)
        }
        throw PeerError("no email arrived within 10 s")
    }
}
