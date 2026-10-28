package org.example.app.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.app.core.account.AccountError
import org.example.app.core.account.AccountService
import org.example.app.core.account.DevicePlatform
import org.example.app.core.account.InviteLink
import org.example.app.core.account.LinkCode
import org.example.app.core.account.LinkRequest
import org.example.app.core.account.OnboardingFlow
import org.example.app.core.account.OnboardingStep
import org.example.app.core.account.ServerAddress
import org.example.app.core.account.ServerChoice
import org.example.app.core.account.UsernameCheck
import java.time.Instant

/** Longest scanned or pasted text read at all (a QR code holds about 3 KB). */
private const val MAX_INPUT_LENGTH = 2048

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val canCreateAccount: Boolean = true,
    val serverHost: String = "",
    val supportsPasskeys: Boolean = true,
    val busy: Boolean = false,
    val error: AccountError? = null,
    val usernameCheck: UsernameCheck? = null,
    val linkCode: LinkCode? = null,
    val linkExpired: Boolean = false,
    val recoveryCodeSent: Boolean = false,
)

/**
 * Drives the welcome, sign-up, linking and lost-device screens over [AccountService] and [OnboardingFlow].
 * [saved] keeps the link code on screen across a recreated view model.
 */
class OnboardingViewModel(
    private val service: AccountService,
    platform: DevicePlatform = DevicePlatform.Android,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val flow = OnboardingFlow(platform)
    private val _state = MutableStateFlow(snapshot().copy(linkCode = restoreLinkCode()))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()
    private var linkJob: Job? = null

    private fun restoreLinkCode(): LinkCode? {
        val text = saved.get<String>(KEY_LINK_TEXT) ?: return null
        val until = saved.get<Long>(KEY_LINK_UNTIL) ?: return null
        return LinkCode(text, Instant.ofEpochSecond(until))
    }

    private fun saveLinkCode(code: LinkCode?) {
        saved[KEY_LINK_TEXT] = code?.qrText
        saved[KEY_LINK_UNTIL] = code?.expiresAt?.epochSecond
    }

    private fun snapshot() = OnboardingUiState(
        step = flow.step,
        canCreateAccount = flow.canCreateAccount,
        serverHost = (service.currentServer ?: service.defaultServer).host,
        supportsPasskeys = service.supportsPasskeys,
    )

    private fun publish(change: (OnboardingUiState) -> OnboardingUiState = { it }) =
        _state.update { change(it.copy(step = flow.step, serverHost = (service.currentServer ?: service.defaultServer).host, supportsPasskeys = service.supportsPasskeys, busy = false)) }

    /** Runs [block] with the busy flag set; an [AccountError] becomes the error shown on the step. */
    private fun run(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountError) {
                publish { it.copy(error = e) }
            }
        }
    }

    fun getStarted() { flow.getStarted(); publish { it.copy(error = null) } }
    fun alreadyHaveAccount() { flow.alreadyHaveAccount(); publish { it.copy(error = null) } }
    fun noOldDevice() { linkJob?.cancel(); flow.noOldDevice(); publish { it.copy(error = null) } }
    fun permissionsHandled() { flow.permissionsHandled(); publish() }

    fun back() {
        linkJob?.cancel()
        flow.back()
        publish { it.copy(error = null, usernameCheck = null, recoveryCodeSent = false) }
    }

    /** A typed server address, or null (and an error on the step) when it breaks [ServerAddress]'s rules. */
    private fun validAddress(text: String): String? {
        val address = ServerAddress.parse(text)
        if (address == null) _state.update { it.copy(error = AccountError.InvalidServerAddress, busy = false) }
        return address?.hostPort
    }

    fun chooseDefaultServer() = run {
        val policy = service.connect(service.defaultServer.host)
        flow.choose(ServerChoice.DefaultServer, policy.requiresInvite)
    }

    fun chooseAddress(text: String) {
        val host = validAddress(text) ?: return
        run {
            val policy = service.connect(host)
            flow.choose(ServerChoice.Address(host), policy.requiresInvite)
        }
    }

    /** An invite link or typed code, from the clipboard or a scanned QR code. False when it is neither. */
    fun chooseInviteText(text: String): Boolean {
        if (text.length > MAX_INPUT_LENGTH) return false
        val invite = InviteLink.parse(text) ?: return false
        val named = invite.host
        val host = if (named == null) service.defaultServer.host else (ServerAddress.parse(named)?.hostPort ?: return false)
        run {
            service.connect(host)
            service.redeemInvite(invite.code)
            flow.choose(ServerChoice.Invite(invite), requiresInvite = false)
        }
        return true
    }

    fun submitInvite(code: String) = run {
        service.redeemInvite(code)
        flow.inviteAccepted()
    }

    fun checkUsername(name: String) {
        if (name.isEmpty()) { _state.update { it.copy(usernameCheck = null) }; return }
        viewModelScope.launch {
            val result = try { service.checkUsername(name) } catch (e: AccountError) { UsernameCheck.Unknown }
            _state.update { it.copy(usernameCheck = result) }
        }
    }

    fun submitAccount(name: String, username: String, email: String) = run {
        service.startSignUp(name.trim(), username, email.trim())
        flow.accountSubmitted()
    }

    fun verifyEmail(code: String) = run {
        service.verifyEmail(code)
        flow.emailVerified()
    }

    fun createPasskey() = run {
        service.finishSignUp()
        flow.passkeyCreated()
    }

    /** "Use an email code instead" and the no-passkey Continue: finishes sign-up without registering a passkey. */
    fun useEmailCodeInstead() = run {
        service.finishSignUpWithEmailCode()
        flow.passkeyCreated()
    }

    /**
     * Shows a link code when the linking screen opens, but only if none is on screen or being
     * waited for: opening the screen again (rotation) must not replace the code the other phone
     * may already be scanning. A code restored from [saved] is waited on, not remade.
     */
    fun ensureLinking() {
        if (linkJob?.isActive == true) return
        val restored = _state.value.linkCode
        if (restored == null || !restored.expiresAt.isAfter(Instant.now()) || _state.value.linkExpired) { startLinking(); return }
        linkJob = viewModelScope.launch { awaitLinkApproval() }
    }

    private suspend fun awaitLinkApproval() {
        try {
            service.waitForLinkApproval()
            saveLinkCode(null)
            flow.linked()
            publish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountError) {
            publish { it.copy(error = if (e == AccountError.LinkExpired) null else e, linkExpired = e == AccountError.LinkExpired) }
        }
    }

    /** New device: show the code and wait for the other phone to approve it. */
    fun startLinking() {
        linkJob?.cancel()
        saveLinkCode(null)
        _state.update { it.copy(busy = true, error = null, linkExpired = false, linkCode = null) }
        linkJob = viewModelScope.launch {
            try {
                val code = service.makeLinkCode()
                saveLinkCode(code)
                _state.update { it.copy(linkCode = code, busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountError) {
                publish { it.copy(error = e) }
                return@launch
            }
            awaitLinkApproval()
        }
    }

    fun changeLinkServer(text: String) {
        val host = validAddress(text) ?: return
        run {
            service.connect(host)
            startLinking()
        }
    }

    fun signInWithPasskey() = run {
        service.signInWithPasskey()
        flow.recovered()
    }

    fun sendRecoveryCode(email: String) = run {
        service.sendRecoveryCode(email.trim())
        _state.update { it.copy(recoveryCodeSent = true) }
    }

    fun recover(email: String, code: String) = run {
        service.recover(email.trim(), code)
        flow.recovered()
    }

    override fun onCleared() { linkJob?.cancel() }

    private companion object {
        const val KEY_LINK_TEXT = "linkText"
        const val KEY_LINK_UNTIL = "linkUntil"
    }
}

data class LinkApprovalUiState(
    val request: LinkRequest? = null,
    val busy: Boolean = false,
    val error: AccountError? = null,
    val approved: Boolean = false,
    /** The screen should run the fingerprint, face or screen-lock prompt and answer with [LinkApprovalViewModel.biometricResult]. */
    val awaitingBiometric: Boolean = false,
    /** Counts rejected scans; the screen re-creates its scanner on each, so it reads again. */
    val rejections: Int = 0,
)

/**
 * The approving phone: read the scanned code, show who is asking, confirm with a strong
 * biometric, then approve. The view model holds no Context: [approve] raises
 * [LinkApprovalUiState.awaitingBiometric], the screen runs the prompt with its current Activity,
 * and [biometricResult] continues. Nothing is sent if the host is wrong or the check fails.
 */
class LinkApprovalViewModel(
    private val service: AccountService,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(LinkApprovalUiState())
    val state: StateFlow<LinkApprovalUiState> = _state.asStateFlow()
    private var lastRejected: String? = null
    private var lastRejectedAt = 0L

    /** Called with the text of a scanned QR code; the service refuses other servers and expired codes. */
    fun scanned(text: String) {
        if (_state.value.busy || _state.value.request != null) return
        // The same wrong picture arrives in every frame; read it again only after a pause.
        if (text == lastRejected && nowMillis() - lastRejectedAt < REPEAT_IGNORE_MS) return
        if (text.length > MAX_INPUT_LENGTH) { reject(text, AccountError.LinkExpired); return }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val request = service.linkRequest(text)
                _state.update { it.copy(request = request, busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountError) {
                reject(text, e)
            }
        }
    }

    private fun reject(text: String, error: AccountError) {
        lastRejected = text.take(MAX_INPUT_LENGTH)
        lastRejectedAt = nowMillis()
        _state.update { it.copy(error = error, busy = false, rejections = it.rejections + 1) }
    }

    fun approve() {
        if (_state.value.request == null || _state.value.busy) return
        _state.update { it.copy(busy = true, error = null, awaitingBiometric = true) }
    }

    /** The screen's answer to [LinkApprovalUiState.awaitingBiometric]. */
    fun biometricResult(result: BiometricResult) {
        val request = _state.value.request ?: return
        if (!_state.value.awaitingBiometric) return
        _state.update { it.copy(awaitingBiometric = false) }
        when (result) {
            BiometricResult.Success -> viewModelScope.launch {
                try {
                    service.approveLink(request)
                    _state.update { it.copy(approved = true, busy = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AccountError) {
                    _state.update { it.copy(error = e, busy = false) }
                }
            }
            BiometricResult.Cancelled -> _state.update { it.copy(busy = false) }
            BiometricResult.Unavailable -> _state.update { it.copy(error = AccountError.BiometricsUnavailable, busy = false) }
            BiometricResult.Failed -> _state.update { it.copy(error = AccountError.Server, busy = false) }
        }
    }

    /** Forget the scanned code so the person can scan again. */
    fun reset() { _state.value = LinkApprovalUiState(rejections = _state.value.rejections) }

    private companion object {
        const val REPEAT_IGNORE_MS = 2_000L
    }
}
