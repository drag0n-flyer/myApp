package org.example.app.ui.onboarding

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.example.app.R

/** How the fingerprint, face or screen-lock check ended. Only [Unavailable] means nothing is set up. */
enum class BiometricResult { Success, Cancelled, Unavailable, Failed }

/** What to show for the check, decided from the API level and what the device has set up. */
sealed interface BiometricPlan {
    /** API 30+: one prompt that takes a strong biometric or the screen lock. */
    data class Combined(val authenticators: Int) : BiometricPlan
    /** API 29: a strong biometric; "Use screen lock" opens the credential prompt when [credentialFallback]. */
    data class StrongThenCredential(val credentialFallback: Boolean) : BiometricPlan
    /** API 29 with no strong biometric enrolled but a screen lock. */
    data object CredentialOnly : BiometricPlan
    data class Blocked(val result: BiometricResult) : BiometricPlan
}

object BiometricPolicy {
    /** [can] is `BiometricManager.canAuthenticate` for the given authenticators. */
    fun plan(sdk: Int, can: (Int) -> Int): BiometricPlan {
        if (sdk >= 30) {
            val both = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
            val code = can(both)
            return if (code == BiometricManager.BIOMETRIC_SUCCESS) BiometricPlan.Combined(both) else blocked(code)
        }
        // API 29 cannot combine the two, so strong comes first with the screen lock as the fallback.
        val strong = can(BIOMETRIC_STRONG)
        val credential = can(DEVICE_CREDENTIAL)
        return when {
            strong == BiometricManager.BIOMETRIC_SUCCESS ->
                BiometricPlan.StrongThenCredential(credentialFallback = credential == BiometricManager.BIOMETRIC_SUCCESS)
            credential == BiometricManager.BIOMETRIC_SUCCESS -> BiometricPlan.CredentialOnly
            else -> blocked(strong)
        }
    }

    private fun blocked(code: Int) = BiometricPlan.Blocked(
        when (code) {
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED, BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricResult.Unavailable
            else -> BiometricResult.Failed
        },
    )

    /** A `BiometricPrompt` error code as a result; a cancel is never an error. */
    fun resultForError(code: Int): BiometricResult = when (code) {
        BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED -> BiometricResult.Cancelled
        BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, BiometricPrompt.ERROR_HW_NOT_PRESENT -> BiometricResult.Unavailable
        else -> BiometricResult.Failed
    }
}

/**
 * Runs the check with [activity]'s prompt. Never throws except for coroutine cancellation: every
 * other exception is a [BiometricResult.Failed]. Nothing is approved unless this returns Success.
 */
suspend fun confirmWithBiometric(activity: FragmentActivity): BiometricResult = try {
    val manager = BiometricManager.from(activity)
    when (val plan = BiometricPolicy.plan(Build.VERSION.SDK_INT, manager::canAuthenticate)) {
        is BiometricPlan.Blocked -> plan.result
        is BiometricPlan.Combined -> prompt(activity, plan.authenticators, negative = null)
        BiometricPlan.CredentialOnly -> prompt(activity, DEVICE_CREDENTIAL, negative = null)
        is BiometricPlan.StrongThenCredential -> {
            // null means the person tapped "Use screen lock".
            val first = promptOnce(
                activity, BIOMETRIC_STRONG,
                negative = activity.getString(if (plan.credentialFallback) R.string.link_confirm_use_lock else R.string.cancel),
                negativeIsFallback = plan.credentialFallback,
            )
            first ?: prompt(activity, DEVICE_CREDENTIAL, negative = null)
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    BiometricResult.Failed
}

private suspend fun prompt(activity: FragmentActivity, authenticators: Int, negative: String?): BiometricResult =
    promptOnce(activity, authenticators, negative, negativeIsFallback = false) ?: BiometricResult.Cancelled

private suspend fun promptOnce(
    activity: FragmentActivity,
    authenticators: Int,
    negative: String?,
    negativeIsFallback: Boolean = false,
): BiometricResult? = suspendCancellableCoroutine { cont ->
    val executor = ContextCompat.getMainExecutor(activity)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            if (cont.isActive) cont.resume(BiometricResult.Success)
        }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (!cont.isActive) return
            cont.resume(
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON && negativeIsFallback) null else BiometricPolicy.resultForError(errorCode),
            )
        }
    }
    try {
        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.link_confirm_title))
            .setSubtitle(activity.getString(R.string.link_confirm_subtitle))
            .setAllowedAuthenticators(authenticators)
            .apply { if (negative != null) setNegativeButtonText(negative) }
            .build()
        cont.invokeOnCancellation { biometricPrompt.cancelAuthentication() }
        biometricPrompt.authenticate(info)
    } catch (e: Exception) {
        if (cont.isActive) cont.resume(BiometricResult.Failed)
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

/** The check bound to whatever Activity is current when it runs (not captured by a view model). */
@Composable
fun rememberBiometricConfirm(): suspend () -> BiometricResult {
    val context by rememberUpdatedState(LocalContext.current)
    return remember {
        {
            val activity = context.findFragmentActivity()
            if (activity == null) BiometricResult.Failed else confirmWithBiometric(activity)
        }
    }
}
