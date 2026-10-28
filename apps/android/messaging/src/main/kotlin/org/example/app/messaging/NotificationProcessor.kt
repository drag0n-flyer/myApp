package org.example.app.messaging

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.GroupId
import org.example.app.contracts.Incoming
import org.example.app.contracts.MlsEngine
import org.example.app.contracts.NotificationOutcome
import org.example.app.contracts.PushPadding
import org.example.app.contracts.PushPayloadHandler
import org.example.app.contracts.RingOutcome
import org.example.app.core.AppBrand
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.content.TextLimit
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.MessageRecord
import org.example.app.messaging.ports.PayloadHash
import org.example.app.messaging.ports.RawInboxRecord
import org.example.app.messaging.ports.Repository
import org.example.app.messaging.sync.AppliedResult
import org.example.app.messaging.sync.IncomingHandler
import org.example.app.messaging.sync.InboxProcessor
import org.example.app.messaging.sync.LocalDevice
import org.example.app.messaging.sync.Membership
import org.example.app.messaging.sync.OwnDeviceNotices

/** How a call ended, as this device learned it over the live connection. Local knowledge only. */
class EndedCall(val reason: String, val answered: Boolean)

/** What this device already knows about a call's end. Reads local state: a push handler must not use the network. */
fun interface EndedCalls {
    fun endOf(callId: String): EndedCall?
}

/** The text of previews. The app builds it from its string resources; the defaults are English. */
data class NotificationStrings(
    val photo: String = "Photo",
    val voiceMessage: String = "Voice message",
    val file: String = "File",
    val unsupported: String = "Update ${AppBrand.NAME} to see this message",
)

/**
 * Turns a push into an outcome and stores what it decrypted, so the app never processes it again. Port of the
 * iPhone `NotificationProcessor`. Never logs payloads, text or names.
 *
 * Locking: `PushDispatcher` holds `MlsGate` around the whole of [onMessagePush] and [onCallPush], and the lock is
 * not re-entrant. So these two never take the [org.example.app.messaging.ports.MlsLock] and make no network call.
 * [fetchOutcomes] is the opposite: it runs outside the gate, and `InboxProcessor` takes the lock for each decrypt.
 */
class NotificationProcessor(
    private val me: LocalDevice,
    private val mls: MlsEngine,
    private val repo: Repository,
    private val clock: Clock,
    private val handler: IncomingHandler,
    private val membership: Membership,
    private val sealer: ChatCallSealer,
    private val inbox: InboxProcessor,
    private val endedCalls: EndedCalls = EndedCalls { null },
    private val strings: NotificationStrings = NotificationStrings(),
) : PushPayloadHandler {

    override suspend fun onMessagePush(padded: ByteArray): NotificationOutcome {
        val payload = PushPadding.unpad(padded) ?: return NotificationOutcome.Generic
        if (payload.isEmpty()) return NotificationOutcome.FetchInbox
        return try {
            run(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            NotificationOutcome.Generic
        }
    }

    private suspend fun run(payload: ByteArray): NotificationOutcome {
        val hash = PayloadHash.of(payload)
        val known = repo.rawItems(500).firstOrNull { it.payload.contentEquals(payload) }
        // A Welcome needs the server's conversation ID and mode, which the push does not carry: the inbox sync does it.
        if (if (known != null) known.kind == "welcome" else sniffsAsWelcome(payload)) return NotificationOutcome.FetchInbox
        if (repo.isProcessed(hash)) return NotificationOutcome.Nothing
        if (known != null && handler.isEchoedOwnCommit(known, repo)) return NotificationOutcome.Nothing

        // The engine's state change and its rows are one step, so a timeout never cuts it in half.
        return withContext(NonCancellable) {
            val incoming = try {
                mls.process(payload)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext NotificationOutcome.Generic
            }
            val keys = if (incoming is Incoming.Commit && !incoming.removedMe) {
                try { membership.keys(incoming.group) } catch (_: Exception) { null }
            } else null
            val raw = known ?: rawRecord(incoming, payload)
            val applied = repo.transaction {
                if (!repo.markProcessed(hash, clock.now())) AppliedResult.Ignored
                else handler.apply(incoming, raw, repo, keys)
            }
            decide(applied)
        }
    }

    private fun rawRecord(incoming: Incoming, payload: ByteArray): RawInboxRecord {
        val kind: String
        val group: GroupId
        val epoch: Long
        var identity = ""
        when (incoming) {
            is Incoming.Application -> { kind = "message"; group = incoming.group; epoch = incoming.epoch; identity = incoming.sender }
            is Incoming.Commit -> { kind = "commit"; group = incoming.group; epoch = incoming.newEpoch }
            is Incoming.Joined -> { kind = "welcome"; group = incoming.group; epoch = incoming.epoch }
        }
        return RawInboxRecord(
            cursor = 0, kind = kind, conversationId = repo.conversationByGroup(group.raw)?.serverId.orEmpty(),
            seq = null, epoch = epoch, senderAccount = identity.substringBefore('/'),
            senderDevice = identity.substringAfter('/', ""), payload = payload, sentAt = clock.now(),
        )
    }

    private fun decide(applied: AppliedResult): NotificationOutcome = when (applied) {
        is AppliedResult.Stored -> outcomeFor(applied.message)
        is AppliedResult.Commit, is AppliedResult.Joined -> ownDeviceOutcome() ?: NotificationOutcome.Nothing
        // Receipts, edits, deletes and reactions never show anything; neither does a call signal in the inbox.
        else -> NotificationOutcome.Nothing
    }

    private fun outcomeFor(message: MessageRecord): NotificationOutcome {
        if (message.senderAccount == me.accountId || message.senderAccount == "system" || message.deleted) return NotificationOutcome.Nothing
        if (repo.isBlocked(message.senderAccount)) return NotificationOutcome.Nothing
        if (repo.chat(message.chatId)?.muted == true) return NotificationOutcome.Nothing
        val contact = repo.contact(message.senderAccount)
        if (contact == null || !contact.isContact) return NotificationOutcome.Request(message.chatId)
        val name = contact.displayName.ifEmpty { contact.username }
        return NotificationOutcome.Message(message.chatId, name, preview(message))
    }

    private fun preview(message: MessageRecord): String = when (val e = ContentCodec.decode(message.envelope)) {
        is ContentEnvelope.Text -> TextLimit.truncate(e.t, PREVIEW_LIMIT)
        is ContentEnvelope.Photo -> strings.photo
        is ContentEnvelope.Voice -> strings.voiceMessage
        is ContentEnvelope.File -> if (e.name.isEmpty()) strings.file else TextLimit.truncate(e.name, PREVIEW_LIMIT)
        else -> strings.unsupported
    }

    /** One notice per new own device, with no name (the name needs the network). Marks it shown, so the app does not repeat it. */
    private fun ownDeviceOutcome(): NotificationOutcome? {
        val pending = repo.entries(OwnDeviceNotices.PENDING_PREFIX).filterValues { it.isNotEmpty() }.keys
            .map { it.removePrefix(OwnDeviceNotices.PENDING_PREFIX) }.sorted()
        var fresh = false
        for (id in pending) {
            if (repo.get(OwnDeviceNotices.notifiedKey(id))?.isNotEmpty() != true) fresh = true
            repo.set(OwnDeviceNotices.notifiedKey(id), byteArrayOf(1))
            repo.set(OwnDeviceNotices.pendingKey(id), ByteArray(0))
        }
        return if (fresh) NotificationOutcome.NewOwnDevice(null) else null
    }

    override suspend fun onCallPush(padded: ByteArray): RingOutcome {
        val payload = PushPadding.unpad(padded)?.takeIf { it.isNotEmpty() } ?: return RingOutcome.Drop
        val opened = try {
            sealer.openHoldingLock(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return RingOutcome.Drop
        }
        val ring = opened.payload
        if (ring.kind != CallSignalPayload.Kind.ring) return RingOutcome.Drop
        val from = opened.senderAccountId
        val contact = repo.contact(from)
        // A blocked caller gets nothing, not even a missed-call notice (the server drops such rings once it knows the block).
        if (repo.isBlocked(from)) return RingOutcome.Ignore
        // A stranger never rings: a quiet "Missed call" notice.
        if (contact == null || !contact.isContact) return RingOutcome.SilentMissed(ring.callID, from)
        val name = contact.displayName.ifEmpty { contact.username }
        // Only a call that ended before anyone answered is a missed call. Answered or declined elsewhere is not.
        // TODO(E6): ask the server for `call.status` (design:226) after this returns Ring, outside MlsGate. D7 keeps the network out of the ring gate.
        val end = endedCalls.endOf(ring.callID)
        if (end != null && !end.answered && end.reason in MISSED_REASONS) {
            return RingOutcome.MissedBeforeAnswer(ring.callID, from, name)
        }
        // Answered or declined on another device: the ring is over, show nothing.
        if (end != null) return RingOutcome.Ignore
        return RingOutcome.Ring(ring.callID, from, name, ring.video ?: false)
    }

    /**
     * Fetches the inbox (at most [maxItems]) and returns what is new to show. Outside `MlsGate`: the inbox processor
     * takes the lock for each decrypt and never holds it across the network.
     */
    suspend fun fetchOutcomes(maxItems: Int = 20): List<NotificationOutcome> {
        val outcome = inbox.syncCollecting(maxItems)
        // A fetch that failed before it stored anything must reach the fetch runner, so it retries, whatever the drain
        // found left over from an earlier run. When this run stored some items first, they are shown; the next push or
        // app start fetches the rest.
        if (outcome.failedOutright) throw outcome.failure!!
        return outcome.results.mapNotNull { result ->
            (result as? AppliedResult.Stored)?.let { outcomeFor(it.message) }?.takeIf { it !== NotificationOutcome.Nothing }
        }
    }

    companion object {
        const val PREVIEW_LIMIT = 140
        private val MISSED_REASONS = setOf("hangup", "missed")

        /** MLSMessage framing: version 1, wire format 3 (Welcome). */
        internal fun sniffsAsWelcome(payload: ByteArray): Boolean =
            payload.size >= 4 && payload[0].toInt() == 0 && payload[1].toInt() == 1 && payload[2].toInt() == 0 && payload[3].toInt() == 3
    }
}
