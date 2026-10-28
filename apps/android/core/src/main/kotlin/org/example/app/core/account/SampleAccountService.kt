package org.example.app.core.account

import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Accepts the sample codes below and keeps everything in memory. */
class SampleAccountService(
    platform: DevicePlatform = DevicePlatform.Android,
    private val clock: () -> Instant = Instant::now,
) : AccountService {
    companion object {
        const val SAMPLE_EMAIL_CODE = "123456"
        /** In the real invite format; the iPhone sample uses a looser code. */
        const val SAMPLE_INVITE_CODE = "ABCDE-FGH23"
        const val SAMPLE_LINK_TEXT = "https://home.example.org/link/sample#7Q2K9"
    }

    private val takenNames = setOf("riya", "admin")
    var lastEmailSentTo: String? = null
        private set
    /** The name given to the last [startSignUp]. */
    var displayName: String? = null
        private set
    /** True once [finishSignUp] registered a passkey; [finishSignUpWithEmailCode] leaves it false. */
    var passkeyRegistered = false
        private set
    private val deviceList: MutableList<DeviceInfo>

    override val defaultServer = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    override var currentServer: ServerPolicy? = null
        private set
    override var isSignedIn = false
        private set
    override val supportsPasskeys: Boolean get() = true

    init {
        val now = clock()
        val hourAgo = now.minusSeconds(3600)
        deviceList = when (platform) {
            DevicePlatform.Mac -> mutableListOf(
                DeviceInfo("this", "This Mac", DevicePlatform.Mac, now, true),
                DeviceInfo("iphone", "iPhone", DevicePlatform.IPhone, hourAgo, false),
            )
            DevicePlatform.Android -> mutableListOf(
                DeviceInfo("this", "This phone", DevicePlatform.Android, now, true),
                DeviceInfo("iphone", "iPhone", DevicePlatform.IPhone, hourAgo, false),
            )
            DevicePlatform.IPhone -> mutableListOf(
                DeviceInfo("this", "This iPhone", DevicePlatform.IPhone, now, true),
                DeviceInfo("mac", "MacBook", DevicePlatform.Mac, hourAgo, false),
            )
        }
    }

    override suspend fun connect(host: String): ServerPolicy {
        currentServer = defaultServer
        return defaultServer
    }

    override suspend fun checkUsername(name: String): UsernameCheck = when {
        !UsernameCheck.isValid(name) -> UsernameCheck.Invalid
        name in takenNames -> UsernameCheck.Taken
        else -> UsernameCheck.Available
    }

    override suspend fun redeemInvite(code: String): Boolean {
        if (code != SAMPLE_INVITE_CODE) throw AccountError.InviteInvalid
        return true
    }

    override suspend fun startSignUp(displayName: String, username: String, email: String) {
        if (!UsernameCheck.isValid(username)) throw AccountError.InvalidUsername
        if (username in takenNames) throw AccountError.UsernameTaken
        if (!email.contains("@") || !email.contains(".")) throw AccountError.InvalidEmail
        this.displayName = displayName
        lastEmailSentTo = email
    }

    override suspend fun verifyEmail(code: String) {
        if (code != SAMPLE_EMAIL_CODE) throw AccountError.CodeInvalid
    }

    override suspend fun finishSignUp() {
        passkeyRegistered = true
        isSignedIn = true
    }

    override suspend fun finishSignUpWithEmailCode() { isSignedIn = true }

    override suspend fun signInWithPasskey() { isSignedIn = true }

    override suspend fun sendRecoveryCode(email: String) {
        if (!email.contains("@")) throw AccountError.InvalidEmail
        lastEmailSentTo = email
    }

    override suspend fun recover(email: String, code: String) {
        if (code != SAMPLE_EMAIL_CODE) throw AccountError.CodeInvalid
        isSignedIn = true
    }

    override suspend fun addPasskey() {}

    override suspend fun makeLinkCode() = LinkCode(SAMPLE_LINK_TEXT, clock().plusSeconds(300))

    override suspend fun waitForLinkApproval() { isSignedIn = true }

    override suspend fun linkRequest(qrText: String): LinkRequest {
        val uri = try { URI(qrText) } catch (_: Exception) { throw AccountError.LinkExpired }
        if (uri.path?.startsWith("/link/") != true) throw AccountError.LinkExpired
        if (uri.host != defaultServer.host) throw AccountError.WrongServer
        return LinkRequest(qrText, "New Mac", DevicePlatform.Mac, clock())
    }

    override suspend fun approveLink(request: LinkRequest) {
        deviceList += DeviceInfo(UUID.randomUUID().toString(), request.deviceName, request.platform, clock(), false)
    }

    override suspend fun devices(): List<DeviceInfo> = deviceList.toList()

    override suspend fun removeDevice(id: String) {
        deviceList.removeAll { it.id == id && !it.isThisDevice }
    }

    override suspend fun deleteAccount() = signOutLocally()

    override fun signOutLocally() {
        isSignedIn = false
        currentServer = null
    }
}
