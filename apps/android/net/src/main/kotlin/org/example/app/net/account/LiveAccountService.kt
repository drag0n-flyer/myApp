package org.example.app.net.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.example.app.contracts.Hpke
import org.example.app.contracts.HpkeInfo
import org.example.app.contracts.SecretStoreUnavailable
import org.example.app.contracts.ServerException
import org.example.app.contracts.SnapshotSource
import org.example.app.core.account.AccountError
import org.example.app.core.account.AccountService
import org.example.app.core.account.DeviceInfo
import org.example.app.core.account.DevicePlatform
import org.example.app.core.account.LinkApproval
import org.example.app.core.account.LinkCode
import org.example.app.core.account.LinkRequest
import org.example.app.core.account.LinkedResult
import org.example.app.core.account.ServerAddress
import org.example.app.core.account.UsernameCheck
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import org.example.app.net.Auth
import org.example.app.net.ServerClient
import org.example.app.net.Session
import org.example.app.net.SessionState
import org.example.app.net.SessionStore
import org.example.app.net.api.IdentityApi
import org.example.app.net.api.ProtocolCheck
import org.example.app.net.api.ProtocolResult
import org.example.app.net.api.ServerInfoApi
import org.example.app.net.api.SocialApi
import org.example.app.net.models.NewSession
import org.example.app.net.models.ServerInfo

/**
 * WebAuthn through the system credential manager. Same shape as `org.example.app.auth.Passkeys` (task B6); the
 * app wires one to the other. Both take the server's options JSON unchanged and return the credential JSON.
 * Throws [AccountError.PasskeyUnavailable] or [AccountError.Cancelled].
 */
interface Passkeys {
    suspend fun create(optionsJson: String): String
    suspend fun get(optionsJson: String): String
}

/** How the strong-biometric check on the approving device ended. Nothing is approved unless [Success]. */
enum class ApprovalOutcome { Success, Cancelled, Unavailable, Failed }

/** The fingerprint or face check before a link is approved (`BiometricGate` in `:auth`, wired by the app). */
fun interface ApprovalConfirmer {
    suspend fun confirm(): ApprovalOutcome
}

/**
 * Whether passkeys work on [host]: `PasskeySupport.supported` of `:auth` over the server's
 * `/.well-known/assetlinks.json` ([assetLinksJson] is null when the file could not be fetched).
 */
fun interface PasskeySupportCheck {
    fun supported(host: String, assetLinksJson: String?): Boolean
}

/**
 * `AccountService` over the Go server (server design section 7.1, docs/protocol/device-linking.md).
 *
 * - The session token is stored only through [sessionStore] (the secret store). A `SecretStoreUnavailable` is
 *   an [AccountError.Server]; it never signs anyone out and never creates anything.
 * - Nothing is logged: not tokens, not link codes.
 * - [client] is for the first host; [makeClient] builds the client for another address typed to [connect].
 * - The session token goes only to the session's own host: [ready] rebinds to a client for the stored session's
 *   host, and refuses (as [AccountError.Server]) if the client still does not match.
 * - Not thread safe: call it from one coroutine at a time (the app's main scope).
 */
class LiveAccountService(
    client: ServerClient,
    private val sessionStore: SessionStore,
    private val passkeys: Passkeys,
    private val hpke: Hpke,
    private val snapshots: SnapshotSource,
    private val deviceName: String,
    private val biometrics: ApprovalConfirmer,
    private val passkeySupport: PasskeySupportCheck = PasskeySupportCheck { _, _ -> false },
    defaultServer: ServerPolicy? = null,
    private val makeClient: (ServerAddress) -> ServerClient = { addr ->
        val url = HttpUrl.Builder().scheme(if (addr.secure) "https" else "http").host(addr.host)
            .apply { addr.port?.let { port(it) } }.build()
        ServerClient(url, sessionStore, OkHttpClient())
    },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : AccountService {
    private var client: ServerClient = client
    private val link = LinkFlow(hpke, sleep)

    @Volatile private var forcedOut = false
    @Volatile private var passkeysOk = false
    private var inviteCode: String? = null
    private var signupId: String? = null
    private var pendingDisplayName: String? = null

    /** A claimed link session whose save failed; memory only. A retry saves it and never claims again. */
    private var unsaved: LinkApproved? = null

    /** Called when the server ended this session (401, or this device was removed); not on a local sign-out. */
    var onSignedOut: (() -> Unit)? = null

    /** True once the connected server's protocol is newer than this app knows: nothing more is sent to it. */
    @Volatile var updateRequired: Boolean = false
        private set

    override var defaultServer: ServerPolicy = defaultServer
        ?: ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, hostPortOf(client.baseUrl))
        private set
    override var currentServer: ServerPolicy? = null
        private set

    override val isSignedIn: Boolean get() = !forcedOut && sessionStore.current != null
    override val supportsPasskeys: Boolean get() = passkeysOk

    /** Absent, present or unavailable (the secret store was busy); the UI maps `Unavailable` to a retry screen. */
    val sessionState: SessionState get() = sessionStore.state

    init {
        install(client)
        // Start on the stored session's host, as the iPhone does. Failures here are reported by ready().
        try {
            (sessionStore.state as? SessionState.Present)?.let { bindTo(it.session.host) }
        } catch (_: AccountError) {
        }
    }

    // ---- server

    override suspend fun connect(host: String): ServerPolicy = accountCall {
        // A session that could not be read may exist: do not sign anything in over it.
        if (sessionStore.isUnavailable) throw AccountError.Server
        val address = ServerAddress.parse(host) ?: throw AccountError.InvalidServerAddress
        val wanted = canonical(address)
        // The session token must never reach another host: while a session is stored, only its own host.
        sessionStore.current?.let { s ->
            if (!s.host.equals(wanted, ignoreCase = true)) {
                if (!forcedOut) throw AccountError.WrongServer
                sessionStore.clear()
            }
        }
        val c = if (hostPortOf(client.baseUrl).equals(wanted, ignoreCase = true)) client else makeClient(address)
        val info = ServerInfoApi(c).serverInfo()
        if (c !== client) {
            client = c
            install(c)
        }
        passkeysOk = false
        updateRequired = ProtocolCheck.check(info) == ProtocolResult.UpdateRequired
        if (updateRequired) throw AccountError.Server
        val policy = policyOf(info)
        passkeysOk = passkeySupport.supported(wanted, fetchAssetLinks(c))
        currentServer = policy
        if (wanted.equals(hostPortOf(client.baseUrl), ignoreCase = true) && defaultServer.host.equals(wanted, ignoreCase = true)) {
            defaultServer = policy
        }
        policy
    }

    // ---- sign-up

    override suspend fun checkUsername(name: String): UsernameCheck {
        if (!UsernameCheck.isValid(name)) return UsernameCheck.Invalid
        return try {
            if (IdentityApi(ready()).usernameAvailable(name)) UsernameCheck.Available else UsernameCheck.Taken
        } catch (e: CancellationException) {
            throw e
        } catch (e: ServerException) {
            if (e.code == "invalid_username") UsernameCheck.Invalid else UsernameCheck.Unknown
        } catch (_: Exception) {
            UsernameCheck.Unknown
        }
    }

    override suspend fun redeemInvite(code: String): Boolean = accountCall {
        if (!IdentityApi(ready()).checkInvite(code)) throw AccountError.InviteInvalid
        inviteCode = code
        true
    }

    override suspend fun startSignUp(displayName: String, username: String, email: String) = accountCall {
        signupId = IdentityApi(ready()).signupStart(inviteCode, username, email)
        pendingDisplayName = displayName.trim().ifEmpty { null }
    }

    override suspend fun verifyEmail(code: String) = accountCall {
        val id = signupId ?: throw AccountError.Server
        IdentityApi(ready()).signupVerify(id, code)
    }

    override suspend fun finishSignUp() = finishSignUp(withPasskey = true)

    override suspend fun finishSignUpWithEmailCode() = finishSignUp(withPasskey = false)

    private suspend fun finishSignUp(withPasskey: Boolean) = accountCall {
        val id = signupId ?: throw AccountError.Server
        val api = IdentityApi(ready())
        val credential = if (withPasskey && passkeysOk) {
            credentialObject(passkeys.create(api.signupPasskeyOptions(id).toString()))
        } else {
            null
        }
        val session = api.signupFinish(id, credential, deviceName, PLATFORM)
        persist(session)
        signupId = null
        inviteCode = null
        // The server's signup/start takes no display name yet: set it now that the session exists.
        // A failure here does not fail the sign-up.
        pendingDisplayName?.let { name ->
            try {
                SocialApi(client).updateSettings(name, null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        pendingDisplayName = null
    }

    // ---- sign-in and recovery

    override suspend fun signInWithPasskey() = accountCall {
        if (!passkeysOk) throw AccountError.PasskeyUnavailable
        val api = IdentityApi(ready())
        val options = api.signinPasskeyOptions()
        val credential = credentialObject(passkeys.get(options.options.toString()))
        persist(api.signinPasskeyFinish(options.challengeID, credential, deviceName, PLATFORM))
    }

    override suspend fun sendRecoveryCode(email: String) = accountCall {
        IdentityApi(ready()).signinEmailStart(email)
    }

    override suspend fun recover(email: String, code: String) = accountCall {
        persist(IdentityApi(ready()).signinEmailFinish(email, code, deviceName, PLATFORM))
    }

    override suspend fun addPasskey() = accountCall {
        if (!passkeysOk) throw AccountError.PasskeyUnavailable
        requireSession()
        val api = IdentityApi(ready())
        val credential = credentialObject(passkeys.create(api.addPasskeyOptions().toString()))
        api.addPasskey(credential)
    }

    // ---- linking, new device

    override suspend fun makeLinkCode(): LinkCode {
        unsaved = null
        val c = ready()
        return link.start(IdentityApi(c), hostPortOf(c.baseUrl), deviceName, PLATFORM)
    }

    override suspend fun waitForLinkApproval(): LinkedResult {
        val c = ready()
        // The claim answers once. If saving the session failed on an earlier call, only the save is retried.
        val approved = unsaved ?: link.awaitApproval(IdentityApi(c)).also { unsaved = it }
        accountCall { persist(approved.session, LINK_SAVE_ATTEMPTS) }
        unsaved = null
        // A snapshot that did not open is null here and nothing is imported. A failed import leaves the
        // account without the contact list, as an absent snapshot would.
        var copied = false
        approved.snapshot?.let {
            try {
                snapshots.import(it)
                copied = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return LinkedResult(contactsCopied = copied)
    }

    // ---- linking, approving device

    override suspend fun linkRequest(qrText: String): LinkRequest = accountCall(link = true) {
        val session = requireSession()
        val code = LinkQr.parse(qrText, session.host)
        val info = IdentityApi(ready()).linkInfo(code.linkId, code.secret)
        LinkRequest(qrText, info.deviceName, DevicePlatform.fromWire(info.platform) ?: DevicePlatform.Other, info.createdAt)
    }

    override suspend fun approveLink(request: LinkRequest): LinkApproval = accountCall(link = true) {
        val session = requireSession()
        val code = LinkQr.parse(request.qrText, session.host)
        val api = IdentityApi(ready())
        when (biometrics.confirm()) {
            ApprovalOutcome.Success -> {}
            ApprovalOutcome.Cancelled -> throw AccountError.Cancelled
            ApprovalOutcome.Unavailable, ApprovalOutcome.Failed -> throw AccountError.BiometricsUnavailable
        }
        val sealed = hpke.seal(code.publicKey, HpkeInfo.LINK, snapshots.export())
        // Over the server's limit: approve without a snapshot; the new device just starts with an empty list.
        val fits = sealed.size <= MAX_SEALED_BYTES
        api.linkApprove(code.linkId, code.secret, sealed.takeIf { fits })
        LinkApproval(snapshotSent = fits)
    }

    // ---- devices and account

    override suspend fun devices(): List<DeviceInfo> = accountCall {
        requireSession()
        IdentityApi(ready()).devices().map {
            DeviceInfo(
                it.id, it.name, DevicePlatform.fromWire(it.platform) ?: DevicePlatform.Other,
                it.lastSeenAt ?: it.createdAt, it.current,
            )
        }
    }

    override suspend fun removeDevice(id: String) = accountCall {
        requireSession()
        IdentityApi(ready()).revokeDevice(id)
    }

    override suspend fun deleteAccount() {
        accountCall {
            requireSession()
            IdentityApi(ready()).deleteMe()
        }
        signOutLocally()
    }

    override fun signOutLocally() {
        forcedOut = true
        link.forget()
        unsaved = null
        // If the storage delete fails the stored session stays and the next launch restores it.
        try {
            sessionStore.clear()
        } catch (_: Exception) {
        }
    }

    // ---- helpers

    private fun install(c: ServerClient) {
        c.setOnUnauthorized {
            forcedOut = true
            onSignedOut?.invoke()
        }
    }

    /** The client for the stored session's host. Throws [AccountError.Server] when it cannot be had. */
    private fun ready(): ServerClient {
        if (updateRequired) throw AccountError.Server
        when (val s = sessionStore.state) {
            SessionState.Unavailable -> throw AccountError.Server
            is SessionState.Present -> bindTo(s.session.host)
            SessionState.Absent -> {}
        }
        return client
    }

    /** Makes [client] the one for [host], so the session token cannot go to another host. */
    private fun bindTo(host: String) {
        if (hostPortOf(client.baseUrl).equals(host, ignoreCase = true)) return
        val address = ServerAddress.parse(host) ?: throw AccountError.Server
        val c = makeClient(address)
        if (!hostPortOf(c.baseUrl).equals(host, ignoreCase = true)) throw AccountError.Server
        client = c
        install(c)
    }

    private fun requireSession(): Session {
        if (forcedOut) throw AccountError.Server
        ready()
        val s = sessionStore.current ?: throw AccountError.Server
        if (!hostPortOf(client.baseUrl).equals(s.host, ignoreCase = true)) throw AccountError.Server
        return s
    }

    /**
     * Saves the session. A busy secret store is retried with backoff; if it stays busy nothing is signed in or
     * out. Never overwrites a stored session that could not be read (the store refuses).
     */
    private suspend fun persist(s: NewSession, attempts: Int = SAVE_ATTEMPTS) {
        val session = Session(hostPortOf(client.baseUrl), s.accountID, s.deviceID, s.sessionToken)
        var attempt = 0
        var wait = SAVE_RETRY_MS
        while (true) {
            try {
                sessionStore.save(session)
                break
            } catch (e: SecretStoreUnavailable) {
                if (++attempt >= attempts) throw e
                sleep(wait)
                wait = minOf(wait * 2, SAVE_RETRY_MAX_MS)
            }
        }
        forcedOut = false
    }

    private fun credentialObject(json: String): JsonObject =
        try {
            Json.parseToJsonElement(json) as? JsonObject ?: throw AccountError.Server
        } catch (e: AccountError) {
            throw e
        } catch (_: Exception) {
            throw AccountError.Server
        }

    private suspend fun fetchAssetLinks(c: ServerClient): String? = try {
        c.requestRaw("GET", "/.well-known/assetlinks.json", null, emptyMap(), Auth.None).body.decodeToString()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun policyOf(info: ServerInfo): ServerPolicy {
        val default = when (info.policy.defaultMode) {
            "auto" -> ModePreference.Auto
            else -> ModePreference.Fixed(mode(info.policy.defaultMode))
        }
        val hiding = when (info.policy.defaultIPHiding) {
            "never" -> IPHiding.Never
            "nonContacts" -> IPHiding.NonContacts
            "always" -> IPHiding.Always
            else -> throw AccountError.Server
        }
        return ServerPolicy(default, mode(info.policy.maxMode), hiding, info.requiresInvite, info.host)
    }

    private fun mode(s: String): DataMode = when (s) {
        "ultraLow" -> DataMode.UltraLow
        "dataSaver" -> DataMode.DataSaver
        "balanced" -> DataMode.Balanced
        "high" -> DataMode.High
        else -> throw AccountError.Server
    }

    private companion object {
        val PLATFORM = DevicePlatform.Android.wire
        const val MAX_SEALED_BYTES = 64 * 1024
        const val SAVE_ATTEMPTS = 3
        const val SAVE_RETRY_MS = 250L
        const val SAVE_RETRY_MAX_MS = 4_000L
        /** A claimed link session is worth waiting for: about 11 seconds of backoff before giving up for now. */
        const val LINK_SAVE_ATTEMPTS = 8

        fun canonical(a: ServerAddress): String {
            val default = if (a.secure) 443 else 80
            val h = if (':' in a.host) "[${a.host}]" else a.host
            return if (a.port != null && a.port != default) "$h:${a.port}" else h
        }

        fun hostPortOf(url: HttpUrl): String {
            val h = if (':' in url.host) "[${url.host}]" else url.host
            return if (url.port != HttpUrl.defaultPort(url.scheme)) "$h:${url.port}" else h
        }
    }
}
