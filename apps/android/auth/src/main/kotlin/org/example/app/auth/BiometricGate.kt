package org.example.app.auth

import android.app.KeyguardManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The fingerprint, face or screen-lock check. A strong biometric is always asked first; the screen lock is
 * accepted only as the person's own fallback (API 30+: one combined prompt; API 29: "Use screen lock").
 * Nothing is approved unless the result is [Result.Success]. Never logs.
 */
object BiometricGate {
    /** Only [Unavailable] means nothing is set up. A cancel is never an error. */
    enum class Result { Success, Cancelled, Unavailable, Failed }

    /** What to show, decided from the API level and what the device has set up. */
    sealed interface Plan {
        /** API 30+: one prompt that takes a strong biometric or the screen lock. */
        data class Combined(val authenticators: Int) : Plan
        /** API 29: a strong biometric; "Use screen lock" opens the credential prompt when [credentialFallback]. */
        data class StrongThenCredential(val credentialFallback: Boolean) : Plan
        /** API 29 with no strong biometric enrolled but a screen lock. */
        data object CredentialOnly : Plan
        data class Blocked(val result: Result) : Plan
    }

    /**
     * [can] is `BiometricManager.canAuthenticate` for the given authenticators. [deviceSecure] is
     * `KeyguardManager.isDeviceSecure` (a PIN, pattern or password is set). On API 29 androidx.biometric 1.1.0
     * answers "unsupported" for `DEVICE_CREDENTIAL`, so the screen lock is read from [deviceSecure] and never from [can].
     */
    fun plan(sdk: Int, deviceSecure: Boolean, can: (Int) -> Int): Plan {
        if (sdk >= 30) {
            val both = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
            val code = can(both)
            return if (code == BiometricManager.BIOMETRIC_SUCCESS) Plan.Combined(both) else blocked(code)
        }
        // API 29 cannot combine the two, so strong comes first with the screen lock as the fallback.
        val strong = can(BIOMETRIC_STRONG)
        return when {
            strong == BiometricManager.BIOMETRIC_SUCCESS -> Plan.StrongThenCredential(credentialFallback = deviceSecure)
            deviceSecure && (strong == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED || strong == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) ->
                Plan.CredentialOnly
            else -> blocked(strong)
        }
    }

    private fun blocked(code: Int) = Plan.Blocked(
        when (code) {
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED, BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Result.Unavailable
            else -> Result.Failed
        },
    )

    /** A `BiometricPrompt` error code as a result; a cancel is never an error. */
    fun resultForError(code: Int): Result = when (code) {
        BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED -> Result.Cancelled
        BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, BiometricPrompt.ERROR_HW_NOT_PRESENT -> Result.Unavailable
        else -> Result.Failed
    }

    /**
     * Runs the check with [activity]'s prompt. Never throws except for coroutine cancellation: every other
     * exception is [Result.Failed]. [sdk] and [canAuthenticate] exist for tests.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
        sdk: Int = Build.VERSION.SDK_INT,
        canAuthenticate: ((Int) -> Int)? = null,
        deviceSecure: Boolean? = null,
    ): Result = try {
        val can = canAuthenticate ?: BiometricManager.from(activity)::canAuthenticate
        val secure = deviceSecure ?: (activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true)
        when (val plan = plan(sdk, secure, can)) {
            is Plan.Blocked -> plan.result
            is Plan.Combined -> prompt(activity, sdk, plan.authenticators, title, subtitle, negative = null)
            Plan.CredentialOnly -> prompt(activity, sdk, DEVICE_CREDENTIAL, title, subtitle, negative = null)
            is Plan.StrongThenCredential -> {
                // null means the person tapped "Use screen lock".
                val first = promptOnce(
                    activity, sdk, BIOMETRIC_STRONG, title, subtitle,
                    negative = activity.getString(if (plan.credentialFallback) R.string.biometric_use_screen_lock else R.string.biometric_cancel),
                    negativeIsFallback = plan.credentialFallback,
                )
                first ?: prompt(activity, sdk, DEVICE_CREDENTIAL, title, subtitle, negative = null)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.Failed
    }

    private suspend fun prompt(activity: FragmentActivity, sdk: Int, authenticators: Int, title: String, subtitle: String?, negative: String?): Result =
        promptOnce(activity, sdk, authenticators, title, subtitle, negative, negativeIsFallback = false) ?: Result.Cancelled

    /**
     * The prompt's settings. Below API 30 `setAllowedAuthenticators(DEVICE_CREDENTIAL)` makes `build()` throw, so
     * the screen-lock-only prompt uses the older `setDeviceCredentialAllowed(true)` (deprecated, still supported).
     */
    @Suppress("DEPRECATION")
    internal fun promptInfo(sdk: Int, title: String, subtitle: String?, authenticators: Int, negative: String?): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .apply {
                if (sdk < 30 && authenticators == DEVICE_CREDENTIAL) setDeviceCredentialAllowed(true)
                else setAllowedAuthenticators(authenticators)
            }
            .apply { if (negative != null) setNegativeButtonText(negative) }
            .build()

    private suspend fun promptOnce(
        activity: FragmentActivity,
        sdk: Int,
        authenticators: Int,
        title: String,
        subtitle: String?,
        negative: String?,
        negativeIsFallback: Boolean,
    ): Result? = suspendCancellableCoroutine { cont ->
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(Result.Success)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (!cont.isActive) return
                cont.resume(
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON && negativeIsFallback) null else resultForError(errorCode),
                )
            }
        }
        try {
            val biometricPrompt = BiometricPrompt(activity, executor, callback)
            val info = promptInfo(sdk, title, subtitle, authenticators, negative)
            cont.invokeOnCancellation { biometricPrompt.cancelAuthentication() }
            biometricPrompt.authenticate(info)
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(Result.Failed)
        }
    }
}
