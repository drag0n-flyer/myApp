package org.example.app.core.account

sealed interface ServerChoice {
    data object DefaultServer : ServerChoice
    data class Invite(val link: InviteLink) : ServerChoice
    data class Address(val host: String) : ServerChoice
}

enum class OnboardingStep {
    Welcome, JoinServer, InviteCode, CreateAccount, VerifyEmail, CreatePasskey, Permissions, Done, LinkDevice, LostDevice
}

/** Welcome and sign-up steps. iPhone and Android create accounts; the Mac only links. */
class OnboardingFlow(val platform: DevicePlatform) {
    var step: OnboardingStep = OnboardingStep.Welcome
        private set
    var server: ServerChoice? = null
        private set
    private val history = ArrayDeque<OnboardingStep>()

    val canCreateAccount: Boolean get() = platform != DevicePlatform.Mac

    fun getStarted() {
        if (step == OnboardingStep.Welcome && canCreateAccount) go(OnboardingStep.JoinServer)
    }

    fun alreadyHaveAccount() {
        if (step == OnboardingStep.Welcome) go(OnboardingStep.LinkDevice)
    }

    fun noOldDevice() {
        if (step == OnboardingStep.LinkDevice && canCreateAccount) go(OnboardingStep.LostDevice)
    }

    fun choose(choice: ServerChoice, requiresInvite: Boolean) {
        if (step != OnboardingStep.JoinServer) return
        server = choice
        when (choice) {
            // An invite link already carries the code.
            is ServerChoice.Invite -> go(OnboardingStep.CreateAccount)
            ServerChoice.DefaultServer, is ServerChoice.Address ->
                go(if (requiresInvite) OnboardingStep.InviteCode else OnboardingStep.CreateAccount)
        }
    }

    fun inviteAccepted() = advance(OnboardingStep.InviteCode, OnboardingStep.CreateAccount)
    fun accountSubmitted() = advance(OnboardingStep.CreateAccount, OnboardingStep.VerifyEmail)
    fun emailVerified() = advance(OnboardingStep.VerifyEmail, OnboardingStep.CreatePasskey)
    fun passkeyCreated() = advance(OnboardingStep.CreatePasskey, OnboardingStep.Permissions)
    fun permissionsHandled() = advance(OnboardingStep.Permissions, OnboardingStep.Done)

    fun linked() {
        if (step == OnboardingStep.LinkDevice || step == OnboardingStep.LostDevice) go(OnboardingStep.Done)
    }

    /** Signed back in without the old device. */
    fun recovered() {
        if (step == OnboardingStep.LostDevice) go(OnboardingStep.Done)
    }

    fun back() {
        if (step == OnboardingStep.Done) return
        history.removeLastOrNull()?.let { step = it }
    }

    private fun advance(from: OnboardingStep, to: OnboardingStep) {
        if (step == from) go(to)
    }

    private fun go(next: OnboardingStep) {
        history.addLast(step)
        step = next
    }
}
