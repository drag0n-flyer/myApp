package org.example.app.ui.onboarding

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.example.app.R

/**
 * Asks for a strong biometric (fingerprint or face) with the platform prompt. Returns false when
 * none is enrolled, the person cancels, or it fails: nothing is approved in that case.
 */
suspend fun confirmWithStrongBiometric(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= 30) {
        val manager = context.getSystemService(BiometricManager::class.java)
        if (manager?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) return false
    } else {
        // API 29 has no authenticator choice; the platform prompt itself rejects a device without biometrics.
        if (context.getSystemService(BiometricManager::class.java)?.canAuthenticate() != BiometricManager.BIOMETRIC_SUCCESS) return false
    }
    return suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        val executor = ContextCompat.getMainExecutor(context)
        val builder = BiometricPrompt.Builder(context)
            .setTitle(context.getString(R.string.link_confirm_title))
            .setSubtitle(context.getString(R.string.link_confirm_subtitle))
            .setNegativeButton(context.getString(R.string.cancel), executor) { _, _ -> if (cont.isActive) cont.resume(false) }
        if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        builder.build().authenticate(signal, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { if (cont.isActive) cont.resume(true) }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { if (cont.isActive) cont.resume(false) }
        })
    }
}
