package org.example.app.core.account

import org.example.app.core.settings.ServerPolicy
import java.time.Instant

enum class UsernameCheck {
    Available, Taken, Invalid, Unknown;

    companion object {
        /** 3-32 characters: lowercase letters, digits or underscore, starting with a letter. */
        fun isValid(name: String): Boolean {
            if (name.length !in 3..32 || name.first() !in 'a'..'z') return false
            return name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }
        }
    }
}

sealed class AccountError : Exception() {
    data object InviteRequired : AccountError()
    data object InviteInvalid : AccountError()
    data object UsernameTaken : AccountError()
    data object InvalidUsername : AccountError()
    data object InvalidEmail : AccountError()
    data object CodeInvalid : AccountError()
    data object CodeExpired : AccountError()
    data class TooManyAttempts(val retryAfterSeconds: Int?) : AccountError()
    data object LinkExpired : AccountError()
    data object WrongServer : AccountError()
    data object PasskeyUnavailable : AccountError()
    /** The approving device could not confirm with the fingerprint, face or screen lock. */
    data object BiometricsUnavailable : AccountError()
    data object Cancelled : AccountError()
    data object Network : AccountError()
    data object Server : AccountError()
}

/** Shown as a QR code on a device waiting to be linked. */
data class LinkCode(val qrText: String, val expiresAt: Instant)

/** What the approving device learns from a scanned code, shown before it approves. */
data class LinkRequest(
    val qrText: String,
    val deviceName: String,
    val platform: DevicePlatform,
    /** When the new device asked to be linked, as reported by the server. */
    val requestedAt: Instant? = null,
)

data class DeviceInfo(
    val id: String,
    val name: String,
    val platform: DevicePlatform,
    val lastActive: Instant,
    val isThisDevice: Boolean,
)

/** Account sign-up and linking. Every call throws [AccountError]. `SampleAccountService` now; the Go server later. */
interface AccountService {
    val defaultServer: ServerPolicy
    val currentServer: ServerPolicy?
    val isSignedIn: Boolean
    /** For `currentServer`, else `defaultServer`. */
    val supportsPasskeys: Boolean

    suspend fun connect(host: String): ServerPolicy
    suspend fun checkUsername(name: String): UsernameCheck
    suspend fun redeemInvite(code: String): Boolean
    suspend fun startSignUp(username: String, email: String)
    suspend fun verifyEmail(code: String)
    /** Creates the passkey when supported. */
    suspend fun finishSignUp()
    suspend fun signInWithPasskey()
    suspend fun sendRecoveryCode(email: String)
    suspend fun recover(email: String, code: String)
    suspend fun addPasskey()
    suspend fun makeLinkCode(): LinkCode
    /** Returns when this device is signed in. */
    suspend fun waitForLinkApproval()
    /** Approving side, step 1: read a scanned code. Throws [AccountError.WrongServer] or [AccountError.LinkExpired]. */
    suspend fun linkRequest(qrText: String): LinkRequest
    /** Approving side, step 2: confirm with biometrics, then approve. Throws [AccountError.BiometricsUnavailable]. */
    suspend fun approveLink(request: LinkRequest)
    suspend fun devices(): List<DeviceInfo>
    suspend fun removeDevice(id: String)
    suspend fun deleteAccount()
    fun signOutLocally()
}
