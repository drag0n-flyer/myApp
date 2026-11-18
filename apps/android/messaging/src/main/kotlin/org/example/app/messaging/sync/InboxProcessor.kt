package org.example.app.messaging.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.example.app.contracts.DeliveryApi
import org.example.app.contracts.GroupId
import org.example.app.contracts.InboxItem
import org.example.app.contracts.Incoming
import org.example.app.contracts.MemberKey
import org.example.app.contracts.MembersEvent
import org.example.app.contracts.membersEvent
import org.example.app.contracts.MlsEngine
import org.example.app.contracts.MlsException
import org.example.app.contracts.ServerException
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.ConversationMode
import org.example.app.messaging.ports.GroupMemberRecord
import org.example.app.messaging.ports.GroupStoreRules
import org.example.app.messaging.ports.MlsLock
import org.example.app.messaging.ports.PayloadHash
import org.example.app.messaging.ports.RawInboxRecord
import org.example.app.messaging.ports.Repository

/**
 * Raw-first inbox pipeline (Design 11, 12, 17). Port of the iPhone `InboxProcessor`.
 *
 * Fetched items are stored in `inbox_raw` (with the cursor) and acknowledged only after that write. Each raw item is
 * then processed in cursor order: under the [MlsLock], not cancellable, the engine call and one transaction that
 * writes the rows, marks the payload hash processed and deletes the raw row. The lock is never held across a
 * network call. Idempotent: the `processed` table is keyed by the payload's SHA-256 and messages by their ID.
 *
 * MLS M7 seam: today [stage] is `process` (the engine commits its state at once) and [confirm] does nothing, so an
 * accepted result is kept in memory ([accepted]) until its rows are written; a kill in between loses the item (the
 * retry gets a placeholder). With M7 `stage` returns a staged result, the rows are written, then `confirm` merges it;
 * a kill before `confirm` replays the item.
 */
class InboxProcessor(
    private val mls: MlsEngine,
    private val server: DeliveryApi,
    private val repo: Repository,
    private val lock: MlsLock,
    private val clock: Clock,
    private val conversations: ConversationManager,
    private val handler: IncomingHandler,
    private val membership: Membership,
    /** Posts "New device added" after a drain (D5). Null in the notification processor, which shows its own notice. */
    private val notices: OwnDeviceNotices? = null,
) {
    private val fetchSection = Mutex()
    private val drainSection = Mutex()

    /** Results the engine accepted whose rows are not written yet, by cursor (guarded by [drainSection]). */
    private val accepted = HashMap<Long, Incoming>()

    /** Receives every applied result after its transaction (D5 receipts, D7 call signals and notices). */
    var onApplied: (suspend (AppliedResult) -> Unit)? = null

    /**
     * Receives each `members` item (a group's member list changed) when it is fetched. Such an item has no payload to
     * decrypt: it is never stored raw and never becomes a message. Null drops it. A failing listener loses nothing the
     * server cannot give again: the list and its version are read from `GET /v1/conversations/{id}`.
     */
    var onMembers: (suspend (MembersEvent) -> Unit)? = null

    /** Receives each drain's results as one batch, before [onApplied] (the delivered receipts: one per chat per batch). */
    var onBatch: (suspend (List<AppliedResult>) -> Unit)? = null

    private sealed interface Step {
        data class Done(val applied: AppliedResult?) : Step
        /** Not processed and not dropped (a busy engine, a failed write, a network error): the raw row stays. */
        data object Stop : Step
    }

    /**
     * Fetches the inbox from the stored cursor, stores and acknowledges it, then drains. Returns the number of items
     * fetched. [maxItems] stops fetching after exactly that many. Items stored before a failure are still drained.
     */
    suspend fun sync(maxItems: Int = Int.MAX_VALUE): Int {
        val outcome = syncCollecting(maxItems)
        outcome.failure?.let { throw it }
        return outcome.fetched
    }

    /** What one [syncCollecting] did: the items fetched, the results of the drain, and the fetch failure if there was one. */
    class SyncOutcome(val fetched: Int, val results: List<AppliedResult>, val failure: Exception?) {
        /** This run's fetch failed before it stored anything. [results] may still hold rows an earlier run left. */
        val failedOutright: Boolean get() = failure != null && fetched == 0
    }

    /**
     * [sync] that returns the drain's results (for the notification fetch, which shows what is new) instead of
     * throwing a fetch failure: items stored before the failure are still drained and reported.
     */
    suspend fun syncCollecting(maxItems: Int = Int.MAX_VALUE): SyncOutcome {
        var fetched = 0
        var failure: Exception? = null
        fetchSection.withLock {
            try {
                fetch(maxItems) { fetched = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        val results = drain()
        return SyncOutcome(fetched, results, failure)
    }

    private suspend fun fetch(maxItems: Int, progress: (Int) -> Unit = {}): Int {
        var fetched = 0
        while (fetched < maxItems) {
            val page = server.inbox(storedCursor(), minOf(PAGE_SIZE, maxItems - fetched))
            val last = page.items.maxOfOrNull { it.cursor } ?: break
            // A `members` item has nothing to decrypt: it is an event, not a raw row.
            repo.transaction {
                repo.insertRaw(page.items.filter { it.kind != "members" }.map(::raw))
                repo.set(CURSOR_KEY, last.toString().encodeToByteArray())
            }
            server.ack(last)
            for (event in page.items.mapNotNull { it.membersEvent() }) {
                try {
                    onMembers?.invoke(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The list is read again from the server by its version; nothing else depends on this item.
                }
            }
            fetched += page.items.size
            progress(fetched)
            if (!page.more) break
        }
        return fetched
    }

    private fun storedCursor(): Long? = repo.get(CURSOR_KEY)?.decodeToString()?.toLongOrNull()

    /** Processes the raw rows in cursor order. Stops at the first row that fails for a temporary reason. */
    suspend fun drain(): List<AppliedResult> {
        val results = mutableListOf<AppliedResult>()
        drainSection.withLock {
            scan@ while (true) {
                val items = repo.rawItems(PAGE_SIZE)
                if (items.isEmpty()) break
                for (raw in items) {
                    when (val step = process(raw)) {
                        Step.Stop -> break@scan
                        is Step.Done -> step.applied?.let { results += it }
                    }
                }
            }
        }
        // Network calls (receipts, the device names) go after the lock and the mutexes are released.
        try {
            notices?.post()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The markers stay; the next drain posts them.
        }
        if (results.isNotEmpty()) onBatch?.invoke(results)
        onApplied?.let { callback -> results.forEach { callback(it) } }
        return results
    }

    private suspend fun process(raw: RawInboxRecord): Step {
        try {
            val hash = PayloadHash.of(raw.payload)
            if (repo.isProcessed(hash)) {
                accepted.remove(raw.cursor)
                repo.deleteRaw(raw.cursor)
                return Step.Done(null)
            }
            if (handler.isEchoedOwnCommit(raw, repo)) {
                accepted.remove(raw.cursor)
                drop(raw, hash)
                return Step.Done(null)
            }

            // A Welcome needs the server's mode, fetched before the lock. A failed lookup can be retried while the
            // Welcome is unprocessed; one the server will never answer drops it.
            var serverMode: String? = null
            var group: ConversationManager.GroupJoin? = null
            if (raw.kind == "welcome") {
                try {
                    val info = server.conversation(raw.conversationId)
                    serverMode = info.mode
                    if (info.kind == "group") {
                        when (val decision = GroupJoinCheck.decide(info, conversations.localAccountId, raw.senderAccount, repo)) {
                            is GroupJoinCheck.Decision.Join -> group = decision.join
                            is GroupJoinCheck.Decision.Drop -> {
                                // Not a group this account is in, already joined, or a list that cannot be true: no chat, no placeholder.
                                accepted.remove(raw.cursor)
                                repo.transaction {
                                    if (decision.park != null) handler.park(raw, repo, decision.park)
                                    markAndDelete(raw, hash)
                                }
                                return Step.Done(null)
                            }
                        }
                    }
                } catch (e: ServerException) {
                    if (!ConversationManager.isDefiniteRejection(e.status)) return Step.Stop
                    accepted.remove(raw.cursor)
                    // The server says this account has no such conversation: kept (parked) for the owner, never a "couldn't join" chat.
                    repo.transaction { handler.park(raw, repo, "welcome_lookup_${e.status}"); markAndDelete(raw, hash) }
                    return Step.Done(null)
                }
                // A value outside the allowed set would fail the conversation write on every retry: never guess.
                // Kept and logged, not processed (the owner decides what a newer mode should do).
                if (serverMode !in ConversationMode.all) {
                    repo.transaction { handler.park(raw, repo, "unknown_mode"); markAndDelete(raw, hash) }
                    return Step.Done(null)
                }
            }

            val applied = try {
                lock.withLock { withContext(NonCancellable) { processLocked(raw, hash, serverMode, group) } }
            } catch (need: ListNeeded) {
                // A group message from a sender the stored list does not have: ask the server for the list, once, with the
                // lock released. The decrypted result is kept in [accepted]; a failed lookup leaves the raw row for the next drain.
                if (!fetchList(need.conversationId)) return Step.Stop
                listChecked += raw.cursor
                lock.withLock { withContext(NonCancellable) { processLocked(raw, hash, serverMode, group) } }
            }
            listChecked.remove(raw.cursor)
            return Step.Done(applied)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Step.Stop
        }
    }

    /** The engine call and its rows. Runs under the lock and is not cancellable. */
    private suspend fun processLocked(raw: RawInboxRecord, hash: ByteArray, serverMode: String?, group: ConversationManager.GroupJoin? = null): AppliedResult? {
        // Another path (the notification processor) may have handled this payload since the check above.
        if (repo.isProcessed(hash)) {
            accepted.remove(raw.cursor)
            repo.deleteRaw(raw.cursor)
            return null
        }
        if (handler.isEchoedOwnCommit(raw, repo)) {
            accepted.remove(raw.cursor)
            drop(raw, hash)
            return null
        }
        val incoming = accepted[raw.cursor] ?: try {
            stage(raw.payload)
        } catch (e: MlsException.Storage) {
            throw e // possibly temporary (disk): the raw row stays
        } catch (_: MlsException) {
            // Anything else the engine rejects is permanent for this payload.
            repo.transaction { placeholderOrPark(raw, "mls_error"); markAndDelete(raw, hash) }
            return null
        }
        accepted[raw.cursor] = incoming
        if (raw.cursor !in listChecked) handler.unlistedGroupConversation(incoming, repo)?.let { throw ListNeeded(it) }

        val result: AppliedResult?
        if (incoming is Incoming.Joined) {
            // Never guess the mode (Design 18). A Welcome the server labelled otherwise has no server mode.
            // A failure that will not go away (see [isPermanentJoinFailure]) must not hold up every other chat: the
            // item is parked with a "couldn't open this chat" placeholder and the queue moves on. Anything else (a
            // storage or database error, an unknown error) may be temporary: the raw row stays, the accepted result
            // is kept, and the next drain retries.
            var failure: String? = if (serverMode == null) "not_welcome" else null
            val plan = if (serverMode == null) null else try {
                conversations.planJoin(incoming.group, raw.conversationId, serverMode, group)
            } catch (e: Exception) {
                val name = e.javaClass.simpleName.ifEmpty { "error" }
                if (isPermanentJoinFailure(e)) {
                    failure = name
                } else {
                    // Temporary as far as we can tell: retried, but not forever. The count is stored, so it survives
                    // a restart; the fifth failed drain of the same item parks it like a permanent failure.
                    val tries = failCount(raw.cursor) + 1
                    if (tries < MAX_JOIN_TRIES) {
                        repo.set(failKey(raw.cursor), tries.toString().encodeToByteArray())
                        throw e
                    }
                    failure = "retries_exhausted:$name"
                }
                null
            }
            result = repo.transaction {
                if (!repo.markProcessed(hash, clock.now())) return@transaction null.also { repo.deleteRaw(raw.cursor) }
                repo.deleteRaw(raw.cursor)
                if (plan == null) {
                    handler.park(raw, repo, "join_failed:$failure")
                    handler.storeJoinFailed(raw, repo)
                    return@transaction null
                }
                if (failCount(raw.cursor) != 0) repo.set(failKey(raw.cursor), "0".encodeToByteArray())
                plan.store(repo)
                handler.apply(incoming, raw, repo, plan.keys)
            }
        } else {
            // The members after a commit. The MLS state has advanced, so the item must not be processed again: a
            // failure flags the conversation instead (no safety number until a read works).
            var unreadable: GroupId? = null
            val keys: List<MemberKey>? = if (incoming is Incoming.Commit && !incoming.removedMe) {
                try { membership.keys(incoming.group) } catch (e: CancellationException) { throw e } catch (_: Exception) { unreadable = incoming.group; null }
            } else null
            result = repo.transaction {
                if (!repo.markProcessed(hash, clock.now())) return@transaction null.also { repo.deleteRaw(raw.cursor) }
                repo.deleteRaw(raw.cursor)
                unreadable?.let { g -> repo.conversationByGroup(g.raw) }?.let { Membership.markUnreadable(it.serverId, repo) }
                handler.apply(incoming, raw, repo, keys)
            }
        }
        accepted.remove(raw.cursor)
        confirm(incoming)
        return result
    }

    /** Thrown before any row is written: the sender of a group message is not in the stored list. */
    private class ListNeeded(val conversationId: String) : Exception()

    /** Cursors whose sender lookup is done (guarded by [drainSection]); a sender still unlisted after it is dropped. */
    private val listChecked = HashSet<Long>()

    /**
     * `GET /v1/conversations/{id}` for the members of a group, handed to the handler for a minute (not stored: the group
     * sync writes the list, and the system lines from its difference). False only when the lookup failed for a reason
     * that may pass (the item is tried again); a list that cannot be true, or a refusal, counts as an answer.
     */
    private suspend fun fetchList(conversationId: String): Boolean {
        try {
            val info = server.conversation(conversationId)
            if (info.kind == "group") {
                val members = info.members.map { GroupMemberRecord(it.account, it.role) }
                try {
                    GroupStoreRules.requireMembers(members, info.membersVersion ?: -1)
                    handler.noteFetchedMembers(conversationId, members.map { it.accountId }.toSet(), clock.now())
                } catch (_: IllegalArgumentException) {
                    // A list that cannot be true is no answer: the sender stays unlisted.
                }
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ServerException) {
            return ConversationManager.isDefiniteRejection(e.status)
        } catch (_: Exception) {
            return false
        }
    }

    private fun failKey(cursor: Long) = "$FAIL_PREFIX$cursor"

    private fun failCount(cursor: Long): Int = repo.get(failKey(cursor))?.decodeToString()?.toIntOrNull() ?: 0

    /** M7: `stageIncoming`. Today the engine's `process`, which commits its state at once. */
    private suspend fun stage(payload: ByteArray): Incoming = mls.process(payload)

    /** M7: `confirmIncoming`, after the rows are written. Nothing to do today. */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun confirm(incoming: Incoming) = Unit

    /**
     * A Welcome that cannot be joined is parked with a `joinFailed` placeholder ("Couldn't open this chat"), so the
     * user sees the chat the peer believes exists. Any other item gets an undecryptable placeholder in its
     * conversation, or is parked when that conversation is not known.
     */
    private fun placeholderOrPark(raw: RawInboxRecord, reason: String) {
        if (raw.kind == "welcome") {
            handler.park(raw, repo, reason)
            handler.storeJoinFailed(raw, repo)
            return
        }
        if (!handler.storePlaceholder(raw, repo)) handler.park(raw, repo, reason)
    }

    private fun drop(raw: RawInboxRecord, hash: ByteArray) = repo.transaction { markAndDelete(raw, hash) }

    private fun markAndDelete(raw: RawInboxRecord, hash: ByteArray) {
        repo.markProcessed(hash, clock.now())
        repo.deleteRaw(raw.cursor)
    }

    companion object {
        const val CURSOR_KEY = "inbox.cursor"

        /**
         * The `planJoin` failures retrying cannot fix: no peer, a server ID or group bound to another conversation, and anything the
         * engine rejects (unreadable credentials included) except a storage error. Everything else is temporary.
         */
        internal fun isPermanentJoinFailure(e: Exception): Boolean = when (e) {
            is SyncException.NoPeer, is SyncException.ConversationRebound -> true
            is MlsException.Storage -> false
            is MlsException -> true
            else -> false
        }
        const val PAGE_SIZE = 100

        /** Failed drains of one item (an unknown error while planning a join) before it is parked. */
        const val MAX_JOIN_TRIES = 5
        private const val FAIL_PREFIX = "inbox.fail."

        fun raw(item: InboxItem) = RawInboxRecord(
            cursor = item.cursor, kind = item.kind, conversationId = item.conversationId, seq = item.seq, epoch = item.epoch,
            senderAccount = item.senderAccountId, senderDevice = item.senderDeviceId, payload = item.payload,
            sentAt = item.sentAt.toEpochMilli(),
        )
    }
}
