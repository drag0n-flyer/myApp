package org.example.app.messaging

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.example.app.contracts.CallSealException
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.CallSignalSealer
import org.example.app.contracts.GroupId
import org.example.app.contracts.Incoming
import org.example.app.contracts.MlsEngine
import org.example.app.contracts.OpenedCallSignal
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.MlsLock
import org.example.app.messaging.ports.PayloadHash
import org.example.app.messaging.ports.RawInboxRecord
import org.example.app.messaging.ports.Repository
import org.example.app.messaging.send.OutboxError
import org.example.app.messaging.sync.ConversationManager
import org.example.app.messaging.sync.IncomingHandler

/** A call payload with the sender taken from the MLS credential (`<accountId>/<deviceId>`), never from the server. */
class RecoveredCallSignal(val payload: CallSignalPayload, val senderAccountId: String, val senderDeviceId: String)

/**
 * Call signal sealing (Design 16). A call signal travels in the 1:1 conversation's MLS group as a `callsig`
 * envelope; the call engine moves the sealed bytes through its own transport. Nothing is stored or posted here.
 *
 * Opening takes application messages only (`MlsEngine.processApplication`): a commit, a proposal or a Welcome on a
 * call channel is refused before any group state changes (it arrives through the inbox instead). An application
 * message that is not a call signal was consumed by the engine, so it is stored like an inbox item (never lost) and
 * the call fails. A call payload's hash is marked processed: the same bytes never open twice.
 *
 * Locking: [open] and [openRecoveringSender] take the [MlsLock]. [openHoldingLock] is for a caller that already
 * holds it (the push handler runs under `MlsGate`); the lock is not re-entrant.
 */
class ChatCallSealer(
    private val mls: MlsEngine,
    private val repo: Repository,
    private val lock: MlsLock,
    private val clock: Clock,
    private val conversations: ConversationManager,
    private val handler: IncomingHandler,
) : CallSignalSealer {

    /** MLS-encrypts [payload] in the preferred conversation with [to], creating it if there is none. */
    override suspend fun seal(payload: CallSignalPayload, to: String): ByteArray {
        val conversation = conversations.preferredConversation(to)
        // A chat whose mode disagrees with what it started with is paused: nothing goes into it.
        if (conversation.modeMismatch) throw OutboxError.ModeMismatch
        val envelope = ContentEnvelope.CallSig(UUID.randomUUID().toString().lowercase(), clock.now(), payload)
        val plaintext = ContentCodec.encode(envelope)
        return lock.withLock { withContext(NonCancellable) { mls.encrypt(plaintext, GroupId(conversation.groupId)).ciphertext } }
    }

    /**
     * Throws [CallSealException.WrongSender] unless the MLS sender's account is [from] and the message came in the
     * 1:1 conversation with [from].
     */
    override suspend fun open(data: ByteArray, from: String): OpenedCallSignal {
        val opened = openRecoveringSender(data)
        if (opened.senderAccountId != from) throw CallSealException.WrongSender
        return OpenedCallSignal(opened.payload, opened.senderDeviceId)
    }

    suspend fun openRecoveringSender(data: ByteArray): RecoveredCallSignal =
        lock.withLock { withContext(NonCancellable) { openHoldingLock(data) } }

    /** [openRecoveringSender] for a caller that holds the MLS lock. No network. */
    suspend fun openHoldingLock(data: ByteArray): RecoveredCallSignal {
        val hash = PayloadHash.of(data)
        if (repo.isProcessed(hash)) throw CallSealException.Undecryptable
        // The group comes from the message's clear-text header; the engine refuses a message for any other group.
        val group = mls.groupOf(data) ?: throw CallSealException.Undecryptable
        val incoming = try {
            mls.processApplication(group, data)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw CallSealException.Undecryptable
        }
        val envelope = ContentCodec.decode(incoming.plaintext)
        val sender = incoming.sender
        if (envelope !is ContentEnvelope.CallSig) {
            consume(incoming, data, hash)
            throw CallSealException.Undecryptable
        }
        repo.markProcessed(hash, clock.now())
        // The identity is `<accountId>/<deviceId>`: without both parts it is malformed.
        val slash = sender.indexOf('/')
        if (slash <= 0 || slash == sender.length - 1) throw CallSealException.WrongSender
        val account = sender.substring(0, slash)
        if (!isOneToOneWith(account, incoming.group)) throw CallSealException.WrongSender
        return RecoveredCallSignal(envelope.p, account, sender.substring(slash + 1))
    }

    /**
     * A call signal counts only in the 1:1 conversation with its sender (calls.md), whose chat ID is the sender's
     * account. The caller creates that conversation, and a Welcome that came by push joins the group before the
     * inbox writes its row: with no row, the group must hold this account's devices and the sender's, nobody else.
     */
    private suspend fun isOneToOneWith(account: String, group: GroupId): Boolean {
        repo.conversationByGroup(group.raw)?.let { return it.chatId == account }
        val accounts = try {
            mls.members(group).map { it.substringBefore('/') }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        return accounts == setOf(conversations.localAccountId, account)
    }

    /** The engine already decrypted a message that is not a call signal: store it as the inbox would. */
    private fun consume(incoming: Incoming.Application, data: ByteArray, hash: ByteArray) {
        val account = incoming.sender.substringBefore('/')
        val device = incoming.sender.substringAfter('/', "")
        val raw = RawInboxRecord(
            cursor = 0, kind = "message", conversationId = repo.conversationByGroup(incoming.group.raw)?.serverId.orEmpty(),
            seq = null, epoch = incoming.epoch, senderAccount = account, senderDevice = device, payload = data, sentAt = clock.now(),
        )
        repo.transaction {
            if (repo.markProcessed(hash, clock.now())) handler.apply(incoming, raw, repo)
        }
    }
}
