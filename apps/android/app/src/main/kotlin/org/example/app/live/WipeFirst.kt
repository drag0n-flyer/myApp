package org.example.app.live

import kotlinx.coroutines.CancellationException
import org.example.app.core.account.AccountError
import org.example.app.core.account.AccountService
import org.example.app.core.account.LinkCode
import org.example.app.core.account.LinkedResult
import org.example.app.core.settings.ServerPolicy

/**
 * The live account service, but every step that can sign in first finishes a pending wipe ([finishWipe]), so a new
 * account never meets the old account's data or database key. A wipe that still fails is [AccountError.Server].
 */
class WipeFirst(private val inner: AccountService, private val finishWipe: suspend () -> Unit) : AccountService by inner {
    private suspend fun <T> first(block: suspend () -> T): T {
        try {
            finishWipe()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw AccountError.Server
        }
        return block()
    }

    override suspend fun connect(host: String): ServerPolicy = first { inner.connect(host) }
    override suspend fun startSignUp(displayName: String, username: String, email: String) = first { inner.startSignUp(displayName, username, email) }
    override suspend fun verifyEmail(code: String) = first { inner.verifyEmail(code) }
    override suspend fun finishSignUp() = first { inner.finishSignUp() }
    override suspend fun finishSignUpWithEmailCode() = first { inner.finishSignUpWithEmailCode() }
    override suspend fun signInWithPasskey() = first { inner.signInWithPasskey() }
    override suspend fun recover(email: String, code: String) = first { inner.recover(email, code) }
    override suspend fun makeLinkCode(): LinkCode = first { inner.makeLinkCode() }
    override suspend fun waitForLinkApproval(): LinkedResult = first { inner.waitForLinkApproval() }
}
