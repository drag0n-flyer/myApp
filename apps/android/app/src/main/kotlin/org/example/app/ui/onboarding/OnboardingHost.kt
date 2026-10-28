package org.example.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.app.R
import org.example.app.core.account.OnboardingStep
import org.example.app.ui.qr.PermissionedQrScanner

/** Shows the screen for the view model's current step; [onFinished] runs once the account is ready. */
@Composable
fun OnboardingHost(vm: OnboardingViewModel, onFinished: () -> Unit) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state.step) { if (state.step == OnboardingStep.Done) onFinished() }
    BackHandler(enabled = state.step != OnboardingStep.Welcome && state.step != OnboardingStep.Done) { vm.back() }
    // The link code is shown from the moment the screen opens, and only while it is the current step.
    // Opening the screen again (rotation) keeps the code that is already there.
    LaunchedEffect(state.step) { if (state.step == OnboardingStep.LinkDevice) vm.ensureLinking() }

    Scaffold(Modifier.fillMaxSize()) { padding ->
        val page = Modifier.padding(padding).safeDrawingPadding()
        Box(page) {
            when (state.step) {
                OnboardingStep.Welcome -> WelcomeScreen(state.canCreateAccount, vm::getStarted, vm::alreadyHaveAccount)
                OnboardingStep.JoinServer -> JoinServerScreen(state.error, vm::chooseDefaultServer, vm::chooseInviteText, vm::chooseAddress)
                OnboardingStep.InviteCode -> InviteCodeScreen(state.error, state.busy, vm::submitInvite)
                OnboardingStep.CreateAccount -> CreateAccountScreen(
                    state.serverHost, state.usernameCheck, state.error, state.busy, vm::checkUsername, vm::submitAccount,
                )
                OnboardingStep.VerifyEmail -> VerifyEmailScreen(state.error, state.busy, vm::verifyEmail)
                OnboardingStep.CreatePasskey -> PasskeyScreen(state.supportsPasskeys, state.error, state.busy, vm::createPasskey, vm::useEmailCodeInstead)
                OnboardingStep.Permissions -> PermissionsStep(vm::permissionsHandled)
                OnboardingStep.LinkDevice -> LinkDeviceScreen(
                    state.serverHost, state.linkCode, state.linkExpired, state.error, state.canCreateAccount,
                    onNewCode = vm::startLinking, onChangeServer = vm::changeLinkServer, onNoOldDevice = vm::noOldDevice,
                )
                OnboardingStep.LostDevice -> LostDeviceScreen(
                    state.recoveryCodeSent, state.error, state.busy, vm::signInWithPasskey, vm::sendRecoveryCode, vm::recover,
                )
                OnboardingStep.Done -> {}
            }
        }
    }
}

/**
 * Approving phone: scan the new device's code, show who is asking, approve. [scanner] is the camera view
 * (a fake in tests). The biometric check runs once, inside the service's `approveLink`.
 */
@Composable
fun LinkApprovalFlow(
    vm: LinkApprovalViewModel,
    onDone: () -> Unit,
    scanner: @Composable (onResult: (String) -> Unit) -> Unit = { PermissionedQrScanner(it) },
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state.approved) { if (state.approved) onDone() }
    val request = state.request
    if (request == null) {
        Scaffold { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Text(stringResource(R.string.link_scan_title), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                AccountErrorText(state.error, Modifier.padding(horizontal = 16.dp))
                // A new scanner for each rejected code, so the person can try again.
                Box(Modifier.weight(1f)) { key(state.rejections) { scanner(vm::scanned) } }
            }
        }
    } else {
        Scaffold { padding ->
            Box(Modifier.padding(padding)) {
                LinkApprovalScreen(request, state.error, state.busy, vm::approve, onCancel = { vm.reset(); onDone() })
            }
        }
    }
}
