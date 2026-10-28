package org.example.app.net.account

import kotlinx.coroutines.CancellationException
import org.example.app.contracts.ServerException
import org.example.app.core.account.AccountError

/** Server error codes (server design section 7) as [AccountError]. */
internal object AccountErrors {
    fun map(e: ServerException, link: Boolean = false): AccountError = when (e.code) {
        "invite_required" -> AccountError.InviteRequired
        "invite_invalid" -> AccountError.InviteInvalid
        "username_taken" -> AccountError.UsernameTaken
        "invalid_username" -> AccountError.InvalidUsername
        "invalid_email" -> AccountError.InvalidEmail
        "code_invalid" -> AccountError.CodeInvalid
        "expired" -> if (link) AccountError.LinkExpired else AccountError.CodeExpired
        "forbidden", "not_found" -> if (link) AccountError.LinkExpired else AccountError.Server
        "rate_limited" -> AccountError.TooManyAttempts(e.retryAfter)
        "network" -> AccountError.Network
        else -> AccountError.Server
    }
}

/**
 * Runs [block] and throws only [AccountError] (or coroutine cancellation). A storage failure such as
 * `SecretStoreUnavailable` is [AccountError.Server]: it never signs anyone out and creates nothing.
 */
internal suspend inline fun <T> accountCall(link: Boolean = false, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: AccountError) {
    throw e
} catch (e: ServerException) {
    throw AccountErrors.map(e, link)
} catch (e: Exception) {
    throw AccountError.Server
}
