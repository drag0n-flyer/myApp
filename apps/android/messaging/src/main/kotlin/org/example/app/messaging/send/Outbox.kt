package org.example.app.messaging.send

import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.example.app.contracts.DeliveryApi
import org.example.app.contracts.GroupId
import org.example.app.contracts.MlsEngine
import org.example.app.contracts.ServerException
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.content.TextLimit
import org.example.app.messaging.crypto.EscrowException
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.ConversationMode
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.ports.MessageRecord
import org.example.app.messaging.ports.MlsLock
import org.example.app.messaging.ports.OutboxRecord
import org.example.app.messaging.ports.Repository
import org.example.app.messaging.sync.ConversationManager
import org.example.app.messaging.sync.EscrowSealer
import org.example.app.messaging.sync.LocalDevice
import org.example.app.messaging.sync.ModePin
import org.example.app.messaging.sync.SyncException

/** Why a send did not happen. */
sealed class OutboxError(message: String) : Exception(message) {
    /** A network or server problem; trying again later may work. */
    object Retryable : OutboxError("retryable")
    /** `innerMode` is not known yet and the server is server-readable (Design 18). */
    object WaitingForMode : OutboxError("waitingForMode")
    /** The pinned mode and the server's mode disagree so that sending must stop. */
    object ModeMismatch : OutboxError("modeMismatch")
    /** The server refused the message with this error code. */
    class Rejected(val code: String) : OutboxError("rejected:$code")
    /** The escrow copy could not be made (no key, a refused key, no sealer). */
    object EscrowFailed : OutboxError("escrowFailed")
}

/** Sends content that is not stored locally (receipts, edits, reactions). */
interface ControlSender {
    /** Throws [OutboxError] when the message could not be sent (nothing is queued). */
    suspend fun sendControl(chatId: String, make: (id: String, at: Long) -> ContentEnvelope)
}

/**
 * The send queue (Design 3, 6, 18): one outbox row per stored message, retried with backoff. Port of the iPhone
 * `Outbox`. Every attempt encrypts afresh and posts with the envelope ID as `clientMsgId`, so a retry (also after a
 * kill between the server's answer and the local write) is idempotent on the server. Never logs content, only IDs.
 *
 * Crash points: the message row and the outbox row are one transaction, and the outbox row is removed only after the
 * server accepted the post. A kill before the post leaves the row for the next flush; a kill after the post but before
 * the local write posts the same `clientMsgId` again, which the server answers without storing a second copy.
 *
 * Locking: the encrypt runs under the [MlsLock], not cancellable; the escrow seal and the post do not (they may use
 * the network). A pending commit is never touched here.
 *
 * @param escrow seals the escrow copy of a server-readable chat; null makes such a send fail with
 *   [OutboxError.EscrowFailed] before anything is encrypted.
 * @param syncOnce `InboxProcessor.sync`, run after a `stale_epoch` so the next try starts from the current epoch.
 * @param onEscrowRejected drops the cached escrow key when the server refuses a copy.
 */
class Outbox(
    val me: LocalDevice,
    private val mls: MlsEngine,
    private val server: DeliveryApi,
    private val repo: Repository,
    private val lock: MlsLock,
    private val clock: Clock,
    private val conversations: ConversationManager,
    private val escrow: EscrowSealer?,
    private val syncOnce: suspend () -> Unit,
    private val fold: (String) -> String = { it.lowercase(Locale.ROOT) },
    private val onEscrowRejected: () -> Unit = {},
    private val log: (String) -> Unit = { System.err.println(it) },
) : ControlSender {
    private val flushing = Mutex()

    // Queue

    /**
     * Stores the message as `sending` and adds its outbox row, in one transaction. Text is cut at 4096 grapheme
     * clusters here, the last place before it leaves. Throws [IllegalArgumentException] over the 64 KiB backstop.
     */
    fun enqueue(envelope: ContentEnvelope, chatId: String) {
        val now = clock.now()
        val limited = if (envelope is ContentEnvelope.Text) envelope.copy(t = TextLimit.truncate(envelope.t)) else envelope
        val data = ContentCodec.encode(limited)
        val (text, reply) = when (limited) {
            is ContentEnvelope.Text -> limited.t to limited.r
            is ContentEnvelope.Photo -> "" to limited.r
            is ContentEnvelope.Voice -> "" to limited.r
            is ContentEnvelope.File -> limited.name to limited.r
            else -> "" to null
        }
        val message = MessageRecord(
            id = limited.id, chatId = chatId, conversationId = null, senderAccount = me.accountId, senderDevice = me.deviceId,
            sentAt = limited.at, receivedAt = now, envelope = data, status = "sending", replyTo = reply, folded = fold(text),
        )
        repo.enqueueSend(message, OutboxRecord(limited.id, chatId, data, 0, now))
    }

    /**
     * Queues a control message (edit, delete, reaction, receipt) in the chat's queue, so it stays behind the messages
     * queued before it, is retried like a message and survives a restart. It has no message row: the outbox row's ID
     * is the envelope ID.
     */
    fun enqueueControl(chatId: String, make: (id: String, at: Long) -> ContentEnvelope) {
        val now = clock.now()
        val envelope = make(UUID.randomUUID().toString().lowercase(), now)
        val limited = if (envelope is ContentEnvelope.Edit) envelope.copy(t = TextLimit.truncate(envelope.t)) else envelope
        repo.enqueue(OutboxRecord(limited.id, chatId, ContentCodec.encode(limited), 0, now))
    }

    /** Manual retry of a failed message: it goes to the END of its chat's queue. False when it is not a failed message. */
    fun retry(messageId: String): Boolean = repo.requeueSend(messageId, clock.now())

    /**
     * Sends what is due. Only the oldest queued row of a chat is eligible, so a newer message never overtakes an older
     * one that is waiting or backing off, in this flush or a later one. Throws when the queue cannot be read; an error
     * recording one result is remembered, the other chats go on, and the first such error is rethrown at the end.
     */
    suspend fun flush() {
        if (!flushing.tryLock()) return
        try {
            val blocked = HashSet<String>()
            var firstError: Exception? = null
            var progressed = true
            while (progressed) {
                progressed = false
                for (item in repo.dueOutboxHeads(clock.now())) {
                    if (item.chatId in blocked) continue
                    try {
                        if (process(item)) progressed = true else blocked += item.chatId
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        blocked += item.chatId
                        if (firstError == null) firstError = e
                    }
                }
            }
            firstError?.let { throw it }
        } finally {
            flushing.unlock()
        }
    }

    /** Returns true when the head left the queue (sent or failed). */
    private suspend fun process(item: OutboxRecord): Boolean {
        val queuedAt = repo.message(item.messageId)?.receivedAt ?: clock.now()
        if (clock.now() - queuedAt > MAX_AGE_MS) {
            repo.completeSend(item.messageId, "failed")
            return true
        }
        return when (attempt(ContentCodec.decode(item.envelope), item.chatId)) {
            Outcome.Sent -> true.also { repo.completeSend(item.messageId, "sent") }
            Outcome.Failed -> true.also { repo.completeSend(item.messageId, "failed") }
            Outcome.Waiting -> false
            Outcome.Retry -> {
                if (item.attempts + 1 >= MAX_ATTEMPTS) {
                    repo.completeSend(item.messageId, "failed")
                    return true
                }
                val delaySeconds = minOf(1L shl minOf(item.attempts, 20), 300L)
                repo.bumpOutbox(item.messageId, clock.now() + delaySeconds * 1000)
                false
            }
        }
    }

    /** Not stored, sent now through the chat's preferred conversation. Throws [OutboxError]. */
    override suspend fun sendControl(chatId: String, make: (id: String, at: Long) -> ContentEnvelope) {
        val envelope = make(UUID.randomUUID().toString().lowercase(), clock.now())
        val conversation = try {
            conversations.preferredConversation(chatId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw OutboxError.Retryable
        }
        send(envelope, conversation)
    }

    // Sending

    private enum class Outcome { Sent, Failed, Waiting, Retry }

    private suspend fun attempt(envelope: ContentEnvelope, chatId: String): Outcome = try {
        send(envelope, conversations.preferredConversation(chatId))
        Outcome.Sent
    } catch (e: OutboxError) {
        when (e) {
            OutboxError.Retryable -> Outcome.Retry
            OutboxError.WaitingForMode -> Outcome.Waiting
            OutboxError.ModeMismatch, OutboxError.EscrowFailed, is OutboxError.Rejected -> Outcome.Failed
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Outcome.Retry
    }

    private sealed interface Plan {
        data object Waiting : Plan
        data object Fail : Plan
        data class Send(val escrow: Boolean, val mismatch: Boolean) : Plan
    }

    /** Design 18: the pinned inner mode against the server's mode (docs/protocol/escrow.md). */
    private fun plan(inner: String?, server: String): Plan = when {
        inner == ConversationMode.SERVER_READABLE && server == ConversationMode.SERVER_READABLE -> Plan.Send(escrow = true, mismatch = false)
        inner == ConversationMode.E2EE && server == ConversationMode.E2EE -> Plan.Send(escrow = false, mismatch = false)
        inner == ConversationMode.E2EE -> Plan.Fail
        inner == ConversationMode.SERVER_READABLE -> Plan.Send(escrow = false, mismatch = true)
        server == ConversationMode.E2EE -> Plan.Send(escrow = false, mismatch = false)
        else -> Plan.Waiting
    }

    /**
     * Encrypts, adds the escrow copy when Design 18 says so, and posts. Throws [OutboxError]. The envelope ID is the
     * `clientMsgId`, so calling it again for the same envelope is idempotent on the server.
     *
     * A `stale_epoch` reads the inbox and tries once more. `escrow_required` and `escrow_forbidden` refresh the server's
     * mode with `GET /v1/conversations/{id}`, decide again by the table and try once more; that cannot make an
     * `e2ee`-pinned conversation send escrow.
     */
    suspend fun send(envelope: ContentEnvelope, start: ConversationRecord) {
        var conversation = start
        var staleRetried = false
        var modeRetried = false
        val plaintext = try {
            ContentCodec.encode(envelope)
        } catch (_: IllegalArgumentException) {
            throw OutboxError.Rejected("too_large")
        }
        while (true) {
            val escrowWanted = when (val p = plan(conversation.innerMode, conversation.mode)) {
                Plan.Waiting -> throw OutboxError.WaitingForMode
                Plan.Fail -> {
                    noteMismatch(conversation, paused = true)
                    throw OutboxError.ModeMismatch
                }
                is Plan.Send -> {
                    if (p.mismatch) noteMismatch(conversation, paused = false)
                    p.escrow
                }
            }
            // Checked before encrypting, so a missing sealer does not advance the ratchet.
            val sealer = if (escrowWanted) escrow ?: throw OutboxError.EscrowFailed else null

            val group = GroupId(conversation.groupId)
            val encrypted = try {
                withContext(NonCancellable) { lock.withLock { mls.encrypt(plaintext, group) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw OutboxError.Retryable
            }
            val escrowCopy = try {
                sealer?.seal(plaintext, envelope.at, conversation.serverId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: SyncException.EscrowKeyUnavailable) {
                throw OutboxError.EscrowFailed
            } catch (_: EscrowException) {
                throw OutboxError.EscrowFailed
            } catch (_: Exception) {
                throw OutboxError.Retryable
            }

            try {
                server.postMessage(conversation.serverId, encrypted.epoch, encrypted.ciphertext, escrowCopy, envelope.id)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServerException) {
                when {
                    e.code == "stale_epoch" && !staleRetried -> {
                        staleRetried = true
                        conversation = try {
                            syncOnce()
                            repo.conversation(conversation.serverId) ?: conversation
                        } catch (c: CancellationException) {
                            throw c
                        } catch (_: Exception) {
                            throw OutboxError.Retryable
                        }
                    }
                    e.code == "stale_epoch" -> throw OutboxError.Retryable
                    e.code == "escrow_required" || e.code == "escrow_forbidden" -> {
                        if (modeRetried) {
                            noteMismatch(conversation, paused = false)
                            throw OutboxError.Rejected(e.code)
                        }
                        modeRetried = true
                        onEscrowRejected()
                        conversation = try {
                            val fresh = server.conversation(conversation.serverId)
                            repo.setServerMode(conversation.serverId, fresh.mode)
                            repo.conversation(conversation.serverId) ?: conversation
                        } catch (c: CancellationException) {
                            throw c
                        } catch (_: Exception) {
                            throw OutboxError.Retryable
                        }
                    }
                    else -> {
                        val transient = e.status == 0 || e.status >= 500 || e.status == 429 || e.status == 408 || e.status == 401
                        throw if (transient) OutboxError.Retryable else OutboxError.Rejected(e.code)
                    }
                }
            } catch (_: Exception) {
                throw OutboxError.Retryable
            }
        }
    }

    /**
     * Flags the conversation once; logs the conversation ID only. [paused] adds the one "messages paused" line (the
     * only case that stops sending is a pinned `e2ee` against a server that says `server_readable`).
     */
    private fun noteMismatch(conversation: ConversationRecord, paused: Boolean) {
        val current = repo.conversation(conversation.serverId) ?: conversation
        if (!current.modeMismatch) {
            repo.flagModeMismatch(current.serverId)
            log("mode_mismatch conversation=${current.serverId}")
        }
        if (paused) ModePin.addPausedLine(repo, current, clock.now())
    }

    companion object {
        /** A queued message that failed this many attempts, or sat in the queue this long, is marked `failed`. */
        const val MAX_ATTEMPTS = 10
        const val MAX_AGE_MS = 24L * 3600 * 1000
    }
}
