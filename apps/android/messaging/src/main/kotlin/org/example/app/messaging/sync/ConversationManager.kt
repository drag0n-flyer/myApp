package org.example.app.messaging.sync

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.example.app.contracts.CommitBundle
import org.example.app.contracts.DeliveryApi
import org.example.app.contracts.GroupId
import org.example.app.contracts.MemberKey
import org.example.app.contracts.MlsEngine
import org.example.app.contracts.MlsException
import org.example.app.contracts.ServerException
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ChatRecord
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.ConversationMode
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.ports.MlsLock
import org.example.app.messaging.ports.Repository

/**
 * Creates and joins 1:1 conversations (Design 1, 18), and posts commits with the epoch-conflict retry.
 * Port of the iPhone `ConversationManager`.
 *
 * Locking (Design 12): every step that changes MLS state or group rows runs in [critical]: under the [MlsLock] and
 * not cancellable, so an engine change is never left without its rows. The lock is never held across a network call.
 *
 * @param syncOnce `InboxProcessor.sync`, run after a rejected or unknown commit so the next attempt starts from the
 *   current epoch. It takes the lock itself, so it is never called from inside [critical].
 */
class ConversationManager(
    private val me: LocalDevice,
    private val mls: MlsEngine,
    private val server: DeliveryApi,
    private val repo: Repository,
    private val lock: MlsLock,
    private val clock: Clock,
    private val membership: Membership,
    private val escrow: EscrowSealer? = null,
    private val syncOnce: suspend () -> Unit = {},
) {
    private val creating = Mutex()

    private suspend fun <T> critical(block: suspend () -> T): T = lock.withLock { withContext(NonCancellable) { block() } }

    /** The active conversation with the smallest server ID, created when there is none. Sends a pending `mode` envelope first. */
    suspend fun preferredConversation(contactId: String): ConversationRecord {
        repo.preferredConversation(contactId)?.let { ensureModeAnnounced(it); return it }
        // A second caller waits for the first create instead of starting another.
        return creating.withLock {
            repo.preferredConversation(contactId)?.let { ensureModeAnnounced(it); return@withLock it }
            create(contactId)
        }
    }

    suspend fun ensureModeAnnounced(conversation: ConversationRecord) {
        if (ModePin.isPending(repo, conversation.serverId)) announceMode(conversation)
    }

    // Create

    /** Claim, create, add, commit; store; then the `mode` envelope, before anything else can be sent. */
    suspend fun create(contactId: String): ConversationRecord {
        // 1. KeyPackages for the peer and for my other devices.
        val theirs = server.claimKeyPackages(contactId)
        if (theirs.isEmpty()) throw SyncException.NoKeyPackages
        val mine = server.claimKeyPackages(me.accountId).filterKeys { it != me.deviceId }
        val claimed = LinkedHashMap(theirs).apply { putAll(mine) }

        // 2. The server conversation, then the group.
        val created = server.createConversation(contactId)
        val group = critical { mls.createGroup() }

        // 3. The add commit at epoch 0, with the Welcome.
        val epoch = postCommitWithRetry(created.conversationId, group, claimed.keys.toList(), emptyList()) {
            mls.add(claimed.values.toList(), group)
        }

        // 4. Store. Both modes come from the create response. I started the chat, so it is never a request.
        val record = ConversationRecord(
            serverId = created.conversationId, chatId = contactId, groupId = group.raw, mode = created.mode,
            innerMode = created.mode, modeMismatch = false, epoch = epoch, active = true, createdAt = clock.now(),
        )
        critical {
            val keys = membership.keys(group)
            repo.transaction {
                if (repo.chat(contactId) == null) repo.upsertChat(ChatRecord(contactId, false, 0, false, clock.now()))
                ModePin.setPending(repo, record.serverId, true)
                repo.upsertConversation(record)
                membership.apply(keys, record, repo)
            }
        }

        // 5. The mode envelope. If the post fails the marker stays and preferredConversation sends it first.
        announceMode(record)
        return record
    }

    // Join

    /** Rows a Welcome creates. Built under the lock (engine reads), written by the inbox processor's transaction. */
    class JoinPlan(val chat: ChatRecord, val conversation: ConversationRecord, val keys: List<MemberKey>) {
        /** The chat is created only when it does not exist yet. */
        fun store(repo: Repository) {
            if (repo.chat(chat.id) == null) repo.upsertChat(chat)
            repo.upsertConversation(conversation)
        }
    }

    /**
     * Called by the inbox processor under the lock, after `process` returned `Joined`. [serverMode] was fetched before
     * the lock. `innerMode` stays null until the creator's `mode` envelope arrives.
     * @throws SyncException.NoPeer when no member belongs to another account.
     */
    suspend fun planJoin(group: GroupId, serverConversationId: String, serverMode: String): JoinPlan {
        val keys = membership.keys(group)
        val peer = keys.map { it.identity.substringBefore('/') }.toSet().minus(me.accountId).minOrNull()
            ?: throw SyncException.NoPeer
        val epoch = mls.epoch(group)
        val isContact = repo.contact(peer)?.isContact ?: false
        return JoinPlan(
            chat = ChatRecord(peer, isRequest = !isContact, unread = 0, muted = false, lastActivity = clock.now()),
            conversation = ConversationRecord(
                serverId = serverConversationId, chatId = peer, groupId = group.raw, mode = serverMode, innerMode = null,
                modeMismatch = false, epoch = epoch, active = true, createdAt = clock.now(),
            ),
            keys = keys,
        )
    }

    // Mode envelope

    /** Sends `mode(innerMode)` in [conversation] (after any add commit). */
    suspend fun announceMode(conversation: ConversationRecord) {
        val inner = conversation.innerMode ?: throw SyncException.ModeUnknown
        val envelope = ContentEnvelope.Mode(UUID.randomUUID().toString().lowercase(), clock.now(), inner)
        val plaintext = ContentCodec.encode(envelope)
        // Design 18: escrow only when both the creator's mode and the server's say server_readable. Checked before
        // encrypting, so a missing sealer does not advance the ratchet.
        val needsEscrow = inner == ConversationMode.SERVER_READABLE && conversation.mode == ConversationMode.SERVER_READABLE
        val sealer = if (needsEscrow) escrow ?: throw SyncException.EscrowKeyUnavailable else null
        // Encrypting advances the ratchet, so it is under the lock; the post is not.
        val encrypted = critical { mls.encrypt(plaintext, GroupId(conversation.groupId)) }
        val escrowCopy = sealer?.seal(plaintext, envelope.at, conversation.serverId)
        server.postMessage(conversation.serverId, encrypted.epoch, encrypted.ciphertext, escrowCopy, envelope.id)
        ModePin.setPending(repo, conversation.serverId, false)
    }

    // Commit with the epoch-conflict retry

    /**
     * Builds a commit with [build], posts it at the group's epoch and merges it on success. Returns the new epoch.
     *
     * - `409 stale_epoch`: `commitRejected`, [syncOnce], rebuild; at most [MAX_STALE_RETRIES] retries, then
     *   [SyncException.EpochConflict].
     * - A definite rejection (another 4xx but 408 and 429): `commitRejected`, then the error.
     * - An unknown outcome (transport error, 5xx, 408, 429, a lost reply): the pending commit stays, because the
     *   server may have applied it and its echo merges it (Design 17). The inbox is read right away, then the error.
     *   The same commit is never posted again.
     *
     * [build] and the epoch read run under the lock; the post does not. `commitAccepted` and `setEpoch` are one step.
     */
    suspend fun postCommitWithRetry(
        serverId: String, group: GroupId, addDevices: List<String>, removeDevices: List<String>, build: suspend () -> CommitBundle,
    ): Long {
        for (attempt in 0..MAX_STALE_RETRIES) {
            val (bundle, epoch) = buildCommit(serverId, group, build)
            val newEpoch = try {
                server.postCommit(serverId, epoch, bundle.commit, bundle.welcome, addDevices, removeDevices)
            } catch (e: ServerException) {
                if (e.status == 409 && e.code == "stale_epoch") {
                    critical { mls.commitRejected(group) }
                    if (attempt == MAX_STALE_RETRIES) throw SyncException.EpochConflict
                    syncOnce()
                    continue
                }
                if (isDefiniteRejection(e.status)) {
                    runCatching { critical { mls.commitRejected(group) } }
                } else {
                    readInboxAfterUnknownOutcome()
                }
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                readInboxAfterUnknownOutcome()
                throw e
            }
            critical {
                mls.commitAccepted(group)
                repo.setEpoch(serverId, newEpoch)
                // Best effort: the next update compares the whole stored state. (`create` has no row yet.)
                repo.conversation(serverId)?.let { c -> runCatching { membership.update(c, repo) } }
            }
            return newEpoch
        }
        throw SyncException.EpochConflict
    }

    /**
     * [build] plus the epoch, under the lock. A pending commit from an earlier unknown outcome blocks a new one: read
     * the inbox (its echo merges it); if it is still pending, ask the server: when the server's epoch is not past
     * mine, it never applied that commit and it is discarded; otherwise its echo is still on the way.
     */
    private suspend fun buildCommit(serverId: String, group: GroupId, build: suspend () -> CommitBundle): Pair<CommitBundle, Long> {
        suspend fun attempt() = critical { build() to mls.epoch(group) }
        try {
            return attempt()
        } catch (e: MlsException) {
            if (e !== MlsException.PendingCommit) throw e
        }
        syncOnce()
        try {
            return attempt()
        } catch (e: MlsException) {
            if (e !== MlsException.PendingCommit) throw e
        }
        val serverEpoch = server.conversation(serverId).epoch
        val discarded = critical {
            if (serverEpoch > mls.epoch(group)) false else { mls.commitRejected(group); true }
        }
        if (!discarded) throw SyncException.CommitInFlight
        return attempt()
    }

    private suspend fun readInboxAfterUnknownOutcome() {
        try {
            syncOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next attempt reads it again before building.
        }
    }

    companion object {
        /** One try plus this many retries after a `stale_epoch`. */
        const val MAX_STALE_RETRIES = 3

        /** A 4xx that means the server did not apply the request. 408 and 429 may have been applied or retried. */
        fun isDefiniteRejection(status: Int) = status in 400..499 && status != 408 && status != 429
    }
}
