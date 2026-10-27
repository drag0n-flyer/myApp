package org.example.app.net.account

import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.example.app.contracts.Hpke
import org.example.app.contracts.HpkeInfo
import org.example.app.contracts.X25519KeyPair
import org.example.app.core.account.AccountError
import org.example.app.core.account.LinkCode
import org.example.app.net.api.IdentityApi
import org.example.app.net.models.LinkClaim
import org.example.app.net.models.NewSession
import org.example.app.net.models.LinkTicket

/** Plan 7's `exportSnapshot` and import. Implemented by `DeviceSync` (task D5). */
interface SnapshotSource {
    suspend fun export(): ByteArray
    suspend fun import(bytes: ByteArray)
}

/** What the new device learns when its link is approved. [snapshot] is null when `sealed` was missing or did not open. */
class LinkApproved(val session: NewSession, val snapshot: ByteArray?)

/**
 * The new-device half of device linking. The link key pair and the claim token live here, in memory only,
 * and are dropped on every way out of [awaitApproval].
 */
internal class LinkFlow(
    private val hpke: Hpke,
    private val clock: () -> Instant,
    private val sleep: suspend (Long) -> Unit,
) {
    private var ticket: LinkTicket? = null
    private var keys: X25519KeyPair? = null

    suspend fun start(api: IdentityApi, host: String, deviceName: String, platform: String): LinkCode {
        forget()
        val pair = accountCall { hpke.generateKeyPair() }
        val t = try {
            accountCall(link = true) { api.linkStart(deviceName, platform) }
        } catch (e: Throwable) {
            pair.privateKey.fill(0)
            throw e
        }
        ticket = t
        keys = pair
        return LinkCode(LinkQr.build(host, t.linkId(), t.secret, pair.publicKey), t.expiresAt)
    }

    /** Polls every [POLL_MS] until approved, the ticket's expiry, or cancellation. */
    suspend fun awaitApproval(api: IdentityApi): LinkApproved {
        val t = ticket ?: throw AccountError.LinkExpired
        val pair = keys
        try {
            while (true) {
                val claim = accountCall(link = true) { api.linkClaim(t.linkId(), t.claimToken) }
                if (claim is LinkClaim.Approved) return LinkApproved(claim.session, open(pair, claim.sealed))
                if (!clock().isBefore(t.expiresAt)) throw AccountError.LinkExpired
                sleep(POLL_MS)
            }
        } finally {
            forget()
        }
    }

    fun forget() {
        keys?.privateKey?.fill(0)
        keys = null
        ticket = null
    }

    /** Missing, or fails to open: import nothing (the link still works). */
    private fun open(pair: X25519KeyPair?, sealed: ByteArray?): ByteArray? {
        if (sealed == null || pair == null) return null
        return try {
            hpke.open(pair, HpkeInfo.LINK, sealed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val POLL_MS = 2_000L
    }
}

private fun LinkTicket.linkId() = linkID
