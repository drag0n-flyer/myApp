package org.example.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.example.app.R
import org.example.app.core.account.OnboardingStep
import org.example.app.ui.qr.PermissionedQrScanner

/** Shows the screen for the view model's current step; [onFinished] runs once the account is ready. */
@Composable
fun OnboardingHost(vm: OnboardingViewModel, onFinished: () -> Unit) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state.step) { if (state.step == OnboardingStep.Done) onFinished() }
    BackHandler(enabled = state.step != OnboardingStep.Welcome && state.step != OnboardingStep.Done) { vm.back() }
    var changingServer by rememberSaveable { mutableStateOf(false) }
    // The link code is shown from the moment the screen opens, and only while it is the current step.
    LaunchedEffect(state.step) { if (state.step == OnboardingStep.LinkDevice) vm.startLinking() }

    Scaffold(Modifier.fillMaxSize()) { padding ->
        val page = Modifier.padding(padding).safeDrawingPadding()
        androidx.compose.foundation.layout.Box(page) {
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
                    onNewCode = vm::startLinking, onChangeServer = { changingServer = true }, onNoOldDevice = vm::noOldDevice,
                )
                OnboardingStep.LostDevice -> LostDeviceScreen(
                    state.recoveryCodeSent, state.error, state.busy, vm::signInWithPasskey, vm::sendRecoveryCode, vm::recover,
                )
                OnboardingStep.Done -> {}
            }
        }
    }

    if (changingServer) {
        var host by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { changingServer = false },
            title = { Text(stringResource(R.string.ob_server_address)) },
            text = {
                OutlinedTextField(host, { host = it }, singleLine = true, placeholder = { Text(stringResource(R.string.ob_server_address_hint)) })
            },
            confirmButton = {
                TextButton({ changingServer = false; vm.changeLinkServer(host) }, enabled = host.isNotBlank()) { Text(stringResource(R.string.ob_continue)) }
            },
            dismissButton = { TextButton({ changingServer = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * Approving phone: scan the new device's code, show who is asking, confirm with a fingerprint or
 * face, approve. [scanner] is the camera view (a fake in tests).
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
            androidx.compose.foundation.layout.Column(Modifier.padding(padding).fillMaxSize()) {
                androidx.compose.material3.Text(
                    stringResource(R.string.link_scan_title), Modifier.padding(16.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
                AccountErrorText(state.error, Modifier.padding(horizontal = 16.dp))
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { scanner(vm::scanned) }
            }
        }
    } else {
        Scaffold { padding ->
            androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                LinkApprovalScreen(request, state.error, state.busy, vm::approve, onCancel = { vm.reset(); onDone() })
            }
        }
    }
}

@Composable
fun rememberLinkApprovalConfirm(): suspend () -> Boolean {
    val context = LocalContext.current
    return { confirmWithStrongBiometric(context) }
}
