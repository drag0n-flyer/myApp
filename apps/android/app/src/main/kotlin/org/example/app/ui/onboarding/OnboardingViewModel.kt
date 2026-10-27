package org.example.app.ui.onboarding

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
import org.example.app.core.account.ServerChoice
import org.example.app.core.account.UsernameCheck

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

/** Drives the welcome, sign-up, linking and lost-device screens over [AccountService] and [OnboardingFlow]. */
class OnboardingViewModel(
    private val service: AccountService,
    platform: DevicePlatform = DevicePlatform.Android,
) : ViewModel() {
    private val flow = OnboardingFlow(platform)
    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()
    private var linkJob: Job? = null

    /** The name the person typed; the account service takes only username and email for now. */
    var displayName: String = ""
        private set

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

    fun chooseDefaultServer() = run {
        val policy = service.connect(service.defaultServer.host)
        flow.choose(ServerChoice.DefaultServer, policy.requiresInvite)
    }

    fun chooseAddress(host: String) = run {
        val policy = service.connect(host.trim())
        flow.choose(ServerChoice.Address(host.trim()), policy.requiresInvite)
    }

    /** An invite link or typed code, from the clipboard or a scanned QR code. False when it is neither. */
    fun chooseInviteText(text: String): Boolean {
        val invite = InviteLink.parse(text) ?: return false
        run {
            service.connect(invite.host ?: service.defaultServer.host)
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
        displayName = name.trim()
        service.startSignUp(username, email.trim())
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

    /**
     * "Use an email code instead": finishes sign-up the same way as the no-passkey server case.
     * Whether the service then offers a passkey is the live service's decision (see report).
     */
    fun useEmailCodeInstead() = createPasskey()

    /** New device: show the code and wait for the other phone to approve it. */
    fun startLinking() {
        linkJob?.cancel()
        _state.update { it.copy(busy = true, error = null, linkExpired = false, linkCode = null) }
        linkJob = viewModelScope.launch {
            try {
                val code = service.makeLinkCode()
                _state.update { it.copy(linkCode = code, busy = false) }
                service.waitForLinkApproval()
                flow.linked()
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountError) {
                publish { it.copy(error = if (e == AccountError.LinkExpired) null else e, linkExpired = e == AccountError.LinkExpired) }
            }
        }
    }

    fun changeLinkServer(host: String) = run {
        service.connect(host.trim())
        startLinking()
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
}

data class LinkApprovalUiState(
    val request: LinkRequest? = null,
    val busy: Boolean = false,
    val error: AccountError? = null,
    val approved: Boolean = false,
)

/**
 * The approving phone: read the scanned code, show who is asking, confirm with a strong biometric
 * ([confirm], the platform prompt), then approve. Nothing is sent if the host is wrong or the
 * biometric is missing.
 */
class LinkApprovalViewModel(
    private val service: AccountService,
    private val confirm: suspend () -> Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(LinkApprovalUiState())
    val state: StateFlow<LinkApprovalUiState> = _state.asStateFlow()

    /** Called with the text of a scanned QR code; the service refuses other servers and expired codes. */
    fun scanned(text: String) {
        if (_state.value.busy || _state.value.request != null) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val request = service.linkRequest(text)
                _state.update { it.copy(request = request, busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountError) {
                _state.update { it.copy(error = e, busy = false) }
            }
        }
    }

    fun approve() {
        val request = _state.value.request ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                if (!confirm()) throw AccountError.BiometricsUnavailable
                service.approveLink(request)
                _state.update { it.copy(approved = true, busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountError) {
                _state.update { it.copy(error = e, busy = false) }
            }
        }
    }

    /** Forget the scanned code so the person can scan again. */
    fun reset() { _state.value = LinkApprovalUiState() }
}
