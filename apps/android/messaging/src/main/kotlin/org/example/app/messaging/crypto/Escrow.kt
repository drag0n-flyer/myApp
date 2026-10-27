package org.example.app.messaging.crypto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.example.app.contracts.Hpke
import org.example.app.contracts.HpkeException
import org.example.app.contracts.HpkeInfo
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.sync.EscrowSealer
import org.example.app.messaging.sync.SyncException

sealed class EscrowException(message: String) : Exception(message) {
    /** The server key is not a 32-byte X25519 public key, or the library refused it. */
    object BadKey : EscrowException("badKey")
}

/**
 * Escrow sealing for server-readable chats (docs/protocol/escrow.md): HPKE base mode, DHKEM(X25519, HKDF-SHA256),
 * HKDF-SHA256, ChaCha20-Poly1305, `info = "escrow-v1"`, output `enc (32) || ct`. The cryptography is [Hpke]'s (the
 * Rust library in the app); this file builds the plaintext and fails closed. Owner review: cryptography.
 */
object Escrow {
    private val json = Json

    /**
     * The escrow copy of [body]: HPKE-sealed `{"conversationID","senderAccountID","sentAt","body"}`. [sentAt] is epoch
     * milliseconds and goes out as an RFC 3339 UTC string.
     * @throws EscrowException.BadKey when [serverKey] is not 32 bytes or [hpke] refuses it.
     */
    fun seal(
        body: ContentEnvelope, conversationId: String, senderAccountId: String, sentAt: Long, serverKey: ByteArray, hpke: Hpke,
    ): ByteArray = sealEncoded(ContentCodec.encode(body), conversationId, senderAccountId, sentAt, serverKey, hpke)

    /** [seal] for a body that is already encoded (the exact bytes the MLS message carries). */
    fun sealEncoded(
        body: ByteArray, conversationId: String, senderAccountId: String, sentAt: Long, serverKey: ByteArray, hpke: Hpke,
    ): ByteArray {
        if (serverKey.size != 32) throw EscrowException.BadKey
        val plaintext = plaintext(body, conversationId, senderAccountId, sentAt)
        return try {
            hpke.seal(serverKey, HpkeInfo.ESCROW, plaintext)
        } catch (_: HpkeException) {
            throw EscrowException.BadKey
        }
    }

    internal fun plaintext(body: ByteArray, conversationId: String, senderAccountId: String, sentAt: Long): ByteArray =
        JsonObject(
            mapOf(
                "conversationID" to JsonPrimitive(conversationId),
                "senderAccountID" to JsonPrimitive(senderAccountId),
                "sentAt" to JsonPrimitive(rfc3339(sentAt)),
                "body" to json.parseToJsonElement(body.decodeToString()),
            ),
        ).toString().encodeToByteArray()

    /**
     * The [EscrowSealer] that [org.example.app.messaging.sync.ConversationManager] and the outbox take. A failed key
     * lookup (no key published, a network error) throws; a refused seal drops the cached key so the next try refetches.
     */
    fun sealer(senderAccountId: String, hpke: Hpke, keys: EscrowKeySource): EscrowSealer = EscrowSealer { plaintext, at, conversationId ->
        val key = keys.key()
        try {
            sealEncoded(plaintext, conversationId, senderAccountId, at, key, hpke)
        } catch (e: EscrowException) {
            keys.invalidate()
            throw e
        }
    }

    /** `2023-11-14T22:13:20.000Z` for 1700000000000. Integer arithmetic only, so nothing can round the milliseconds. */
    fun rfc3339(milliseconds: Long): String {
        val days = Math.floorDiv(milliseconds, 86_400_000L)
        val msOfDay = milliseconds - days * 86_400_000L
        // Howard Hinnant's civil_from_days.
        val z = days + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        return "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ".format(
            year, month, day, msOfDay / 3_600_000, msOfDay / 60_000 % 60, msOfDay / 1000 % 60, msOfDay % 1000,
        )
    }
}

/**
 * The server's escrow public key: fetched once through [fetch] (the server info's `encryption.escrowKey`, decoded;
 * null when the server publishes none), cached. One source is shared by everything that seals escrow copies.
 */
class EscrowKeySource(private val fetch: suspend () -> ByteArray?) {
    private val mutex = Mutex()
    private var cached: ByteArray? = null

    /** @throws SyncException.EscrowKeyUnavailable when the server publishes no key. */
    suspend fun key(): ByteArray = mutex.withLock {
        cached ?: (fetch()?.takeIf { it.isNotEmpty() } ?: throw SyncException.EscrowKeyUnavailable).also { cached = it }
    }

    /** Drops the cache, for example after a copy was refused. */
    fun invalidate() { cached = null }
}
