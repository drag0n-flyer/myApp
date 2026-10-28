package org.example.app.net.account

import kotlinx.coroutines.CancellationException
import org.example.app.contracts.Hpke
import org.example.app.contracts.HpkeInfo
import org.example.app.contracts.ServerException
import org.example.app.contracts.X25519KeyPair
import org.example.app.core.account.AccountError
import org.example.app.core.account.LinkCode
import org.example.app.net.api.IdentityApi
import org.example.app.net.models.LinkClaim
import org.example.app.net.models.NewSession
import org.example.app.net.models.LinkTicket

/** What the new device learns when its link is approved. [snapshot] is null when `sealed` was missing or did not open. */
class LinkApproved(val session: NewSession, val snapshot: ByteArray?)

/**
 * The new-device half of device linking. The link key pair and the claim token live here, in memory only,
 * and are dropped on every way out of [awaitApproval].
 */
internal class LinkFlow(
    private val hpke: Hpke,
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

    /**
     * Polls every [POLL_MS] until approved, the server says the code is gone (`expired`, `forbidden`, `not_found`),
     * [MAX_WAIT_MS] have passed, or cancellation. The device clock is never consulted: only the server's answer
     * and the time spent waiting here end the wait. A network error, a 5xx or a rate limit is retried with
     * backoff and never ends linking. The claim itself is never repeated once it has been answered.
     */
    suspend fun awaitApproval(api: IdentityApi): LinkApproved {
        val t = ticket ?: throw AccountError.LinkExpired
        val pair = keys
        try {
            var waited = 0L
            var failures = 0
            while (true) {
                val claim = try {
                    api.linkClaim(t.linkId(), t.claimToken).also { failures = 0 }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AccountError) {
                    throw e
                } catch (e: ServerException) {
                    if (!transient(e)) throw AccountErrors.map(e, link = true)
                    failures++
                    val backoff = minOf(POLL_MS shl minOf(failures - 1, 5), MAX_BACKOFF_MS)
                    val wait = maxOf(backoff, minOf((e.retryAfter ?: 0) * 1_000L, MAX_BACKOFF_MS * 2))
                    if (waited + wait > MAX_WAIT_MS) throw AccountError.LinkExpired
                    sleep(wait)
                    waited += wait
                    continue
                } catch (e: Exception) {
                    throw AccountError.Server
                }
                if (claim is LinkClaim.Approved) return LinkApproved(claim.session, open(pair, claim.sealed))
                if (waited + POLL_MS > MAX_WAIT_MS) throw AccountError.LinkExpired
                sleep(POLL_MS)
                waited += POLL_MS
            }
        } finally {
            forget()
        }
    }

    private fun transient(e: ServerException) = e.code == "network" || e.status >= 500 || e.code == "rate_limited"

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
        const val MAX_BACKOFF_MS = 30_000L

        /** The server's code lives 5 minutes; this is only a safety stop if it never says `expired`. */
        const val MAX_WAIT_MS = 10 * 60_000L
    }
}

private fun LinkTicket.linkId() = linkID
