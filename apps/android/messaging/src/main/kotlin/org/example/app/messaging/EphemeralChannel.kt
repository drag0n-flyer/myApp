package org.example.app.messaging

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.example.app.contracts.ServerEvent
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.Repository
import org.example.app.messaging.sync.LocalDevice

/** Sends one `conv.ephemeral` frame. `:app` wires it to `LiveSocket.sendEphemeral` (a network call). */
fun interface EphemeralTransport {
    suspend fun send(conversationId: String, epoch: Long, payload: ByteArray)
}

/** A typing or presence signal that qualified. [accountId] is the frame's sender, checked to be the chat's other member. */
class EphemeralEvent(val chatId: String, val accountId: String, val body: ContentEnvelope)

/**
 * Typing and presence (design 6.1). The signal is the plain envelope JSON (at most 256 bytes) in the `ciphertext` field
 * of a `conv.ephemeral` frame, relayed live by the server and never stored. It does NOT use MLS: decrypting out of band
 * advances the sender's key generation, and a run of undelivered frames would leave the peer unable to read the sender's
 * stored messages. Nothing here touches the MLS engine or its lock, and nothing is written to the database.
 *
 * A frame is dropped silently when its conversation is unknown, its sender is this account or not the chat's other
 * member (or is blocked), or its payload is over 256 bytes, not JSON, or anything but a valid `typing` or `presence`.
 * The frame's epoch is ignored.
 *
 * The caller feeds frames in with [onFrame] (the socket's event flow has one collector, the app's dispatcher).
 */
class EphemeralChannel(
    private val me: LocalDevice,
    private val repo: Repository,
    private val transport: EphemeralTransport,
) {
    private val _events = MutableSharedFlow<EphemeralEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Qualifying signals. Not replayed: a collector sees only what arrives after it started. */
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
        return try {
            transport.send(conversation.serverId, conversation.epoch, ContentCodec.encode(body))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** Emits the frame on [events] when it qualifies. Never throws but for cancellation. */
    fun onFrame(frame: ServerEvent.ConvEphemeral) {
        try {
            open(frame)?.let { _events.tryEmit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // dropped silently
        }
    }

    private fun open(frame: ServerEvent.ConvEphemeral): EphemeralEvent? {
        if (frame.ciphertext.size > MAX_BYTES) return null
        if (frame.fromAccountId == me.accountId) return null
        val conversation = repo.conversation(frame.conversationId) ?: return null
        // In a one-to-one chat the chat ID is the other person's account.
        if (!conversation.active || frame.fromAccountId != conversation.chatId || repo.isBlocked(frame.fromAccountId)) return null
        val body = ContentCodec.decode(frame.ciphertext)
        if (body !is ContentEnvelope.Typing && body !is ContentEnvelope.Presence) return null
        return EphemeralEvent(conversation.chatId, frame.fromAccountId, body)
    }

    private companion object {
        const val MAX_BYTES = 256
    }
}
