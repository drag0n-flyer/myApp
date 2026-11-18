package org.example.app.ui.lock

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi

/** How a request to the phone's own authentication ended. */
enum class AuthResult {
    Success,

    /** The person dismissed the sheet, or it closed (too many tries, the app left the screen). Not a pass. */
    Cancelled,

    /** The phone cannot ask: no screen lock, or the system refused. Not a pass. */
    Unavailable,
}

/**
 * The phone's own fingerprint, face or screen-lock check (design 6.3). The app keeps no PIN and no secret of its own:
 * the system sheet is the only way to pass. A small interface so tests use [SampleDeviceAuth].
 */
interface DeviceAuth {
    /** The phone has a screen lock (PIN, pattern, password) or an enrolled fingerprint or face to ask with. */
    val available: Boolean

    /** Shows the system sheet; [onResult] is called once, on the main thread. A request while one is open is ignored. */
    fun authenticate(title: String, onResult: (AuthResult) -> Unit)

    /** Closes a sheet that is open (the app left the screen). */
    fun cancel()
}

/**
 * The platform `android.hardware.biometrics.BiometricPrompt` (API 29, our minimum). Device credential as a fallback is
 * asked for differently by API level: `setDeviceCredentialAllowed` on 29 (deprecated from 30) and
 * `setAllowedAuthenticators` from 30. The sheet needs an Activity [context] to attach to.
 */
class PlatformDeviceAuth(
    private val context: Context,
    private val sdk: Int = Build.VERSION.SDK_INT,
    /** Builds and shows the system sheet; tests replace it (Robolectric has no biometric service). */
    private val show: (title: String, cancel: CancellationSignal, callback: BiometricPrompt.AuthenticationCallback) -> Unit =
        { title, cancel, callback ->
            BiometricPrompt.Builder(context).setTitle(title).also { allowDeviceCredential(it, sdk) }.build()
                .authenticate(cancel, context.mainExecutor, callback)
        },
) : DeviceAuth {
    private var signal: CancellationSignal? = null

    override val available: Boolean
        get() = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    override fun authenticate(title: String, onResult: (AuthResult) -> Unit) {
        if (signal != null) return
        if (!available) {
            onResult(AuthResult.Unavailable)
            return
        }
        val cancel = CancellationSignal()
        var answered = false
        fun answer(result: AuthResult) {
            if (answered) return
            answered = true
            signal = null
            onResult(result)
        }
        val callback = PromptCallback(::answer)
        try {
            signal = cancel
            show(title, cancel, callback)
        } catch (e: RuntimeException) {
            signal = null
            answer(AuthResult.Unavailable)
        }
    }

    override fun cancel() {
        signal?.cancel()
        signal = null
    }
}

/** How device credential as a fallback is asked for. */
internal enum class CredentialRequest { DeviceCredentialAllowed, AllowedAuthenticators }

/** `setDeviceCredentialAllowed` on API 29 (deprecated from 30), `setAllowedAuthenticators` from 30. */
internal fun credentialRequestFor(sdk: Int): CredentialRequest =
    if (sdk >= 30) CredentialRequest.AllowedAuthenticators else CredentialRequest.DeviceCredentialAllowed

/** The system's callbacks as results. Only a success is a pass: every error (cancel, lockout, no biometrics, ...) is Cancelled. */
internal class PromptCallback(private val answer: (AuthResult) -> Unit) : BiometricPrompt.AuthenticationCallback() {
    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = answer(AuthResult.Success)

    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = answer(AuthResult.Cancelled)

    // A finger that did not match: the sheet stays open and the person tries again.
    override fun onAuthenticationFailed() {}
}

/** For sample data and tests: no system sheet. Answers with the next of [results] (default Success) at once. */
class SampleDeviceAuth(override var available: Boolean = true, results: List<AuthResult> = emptyList()) : DeviceAuth {
    private val queue = ArrayDeque(results)

    /** How many sheets were asked for. */
    var requests = 0
        private set

    /** Answer later requests with [result]. */
    fun answerWith(vararg result: AuthResult) { queue.clear(); queue.addAll(result) }

    override fun authenticate(title: String, onResult: (AuthResult) -> Unit) {
        requests++
        onResult(if (!available) AuthResult.Unavailable else queue.removeFirstOrNull() ?: AuthResult.Success)
    }

    override fun cancel() {}
}

private fun allowDeviceCredential(builder: BiometricPrompt.Builder, sdk: Int) {
    if (credentialRequestFor(sdk) == CredentialRequest.AllowedAuthenticators && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        allowWeakBiometricOrCredential(builder)
    } else {
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }
}

/** Weak biometrics (many phones' face unlock) are enough: this only decides who sees the screen. */
@RequiresApi(Build.VERSION_CODES.R)
private fun allowWeakBiometricOrCredential(builder: BiometricPrompt.Builder) {
    builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
}
