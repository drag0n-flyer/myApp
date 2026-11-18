package org.example.app.messaging

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.example.app.contracts.GroupId
import org.example.app.contracts.MlsEngine
import org.example.app.contracts.ServerEvent
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.MlsLock
import org.example.app.messaging.ports.Repository
import org.example.app.messaging.sync.LocalDevice

/** Sends one `conv.ephemeral` frame. `:app` wires it to `LiveSocket.sendEphemeral` (a network call: never under the MLS lock). */
fun interface EphemeralTransport {
    suspend fun send(conversationId: String, epoch: Long, ciphertext: ByteArray)
}

/** A typing or presence signal that decrypted. [accountId] is the MLS credential's account, never the frame's claim. */
class EphemeralEvent(val chatId: String, val accountId: String, val body: ContentEnvelope)

/**
 * Typing and presence (design 6.1): an MLS application message in the chat's current epoch, relayed live by the server
 * through the `conv.ephemeral` frame and never stored.
 *
 * Sending encrypts under the [MlsLock] (not cancellable, like a message) and sends outside it. Receiving takes
 * application messages only ([MlsEngine.processApplication]): a commit, proposal or Welcome is refused before any group
 * state changes. Nothing is written to the database: no message row, no processed hash, no unread count.
 *
 * A frame for an unknown conversation, another epoch than the stored one, from a blocked or own account, that fails to
 * decrypt, or whose payload is not a valid `typing` or `presence` envelope is dropped silently.
 *
 * Ratchet caveat: decrypting an application message out of band advances the sender's key generation the same way a
 * stored message does, as call signals already do. Frames the server never delivered (offline device, rate limit) leave
 * generation gaps that the next stored message must tolerate; see the task report.
 *
 * The caller feeds frames in with [onFrame] (the socket's event flow has one collector, which is the app's dispatcher).
 */
class EphemeralChannel(
    private val me: LocalDevice,
    private val mls: MlsEngine,
    private val repo: Repository,
    private val lock: MlsLock,
    private val clock: Clock,
    private val transport: EphemeralTransport,
) {
    private val _events = MutableSharedFlow<EphemeralEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Hashes of the last frames opened, so a replayed frame is dropped whatever the engine does with it. In memory only. */
    private val seen = object : LinkedHashMap<String, Unit>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 256
    }

    /** Decrypted signals. Not replayed: a collector sees only what arrives after it started. */
    val events: SharedFlow<EphemeralEvent> = _events.asSharedFlow()

    /**
     * Sends [body] (a `typing` or `presence` envelope) in the chat's preferred conversation. True when the frame went
     * out; false when there is no conversation or the send failed. Nothing is retried: a signal that missed is stale.
     * @throws IllegalArgumentException for any other envelope kind.
     */
    suspend fun send(chatId: String, body: ContentEnvelope): Boolean {
        require(body is ContentEnvelope.Typing || body is ContentEnvelope.Presence) { "only typing and presence are ephemeral" }
        val conversation = try { repo.preferredConversation(chatId) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            ?: return false
        if (conversation.modeMismatch) return false
        val plaintext = ContentCodec.encode(body)
        val encrypted = try {
            withContext(NonCancellable) { lock.withLock { mls.encrypt(plaintext, GroupId(conversation.groupId)) } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        return try {
            transport.send(conversation.serverId, encrypted.epoch, encrypted.ciphertext)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** Opens one frame and, when it is a valid signal from the chat's other person, emits it on [events]. Never throws but for cancellation. */
    suspend fun onFrame(frame: ServerEvent.ConvEphemeral) {
        try {
            open(frame)?.let { _events.tryEmit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // dropped silently
        }
    }

    private suspend fun open(frame: ServerEvent.ConvEphemeral): EphemeralEvent? {
        if (frame.fromAccountId == me.accountId) return null
        val conversation = repo.conversation(frame.conversationId) ?: return null
        if (!conversation.active || frame.epoch != conversation.epoch) return null
        val group = GroupId(conversation.groupId)
        val hash = org.example.app.messaging.ports.PayloadHash.of(frame.ciphertext).joinToString("") { "%02x".format(it) }
        if (synchronized(seen) { seen.put(hash, Unit) != null }) return null
        val incoming = withContext(NonCancellable) {
            lock.withLock {
                // The group comes from the message's own header and must be this conversation's.
                if (mls.groupOf(frame.ciphertext) != group) null
                else try { mls.processApplication(group, frame.ciphertext) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            }
        } ?: return null
        // The sender is the MLS credential. In a one-to-one chat the chat ID is the other person's account.
        val account = incoming.sender.substringBefore('/')
        if (account == me.accountId || account != conversation.chatId || repo.isBlocked(account)) return null
        val body = ContentCodec.decode(incoming.plaintext)
        if (body !is ContentEnvelope.Typing && body !is ContentEnvelope.Presence) return null
        return EphemeralEvent(conversation.chatId, account, body)
    }
}
