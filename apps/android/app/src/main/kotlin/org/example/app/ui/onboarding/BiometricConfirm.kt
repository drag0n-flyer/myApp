package org.example.app.ui.onboarding

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import org.example.app.R
import org.example.app.auth.BiometricGate

typealias BiometricResult = BiometricGate.Result

/** The check with this screen's title and subtitle; the logic lives in [BiometricGate] (`:auth`). */
suspend fun confirmWithBiometric(activity: FragmentActivity): BiometricResult =
    BiometricGate.authenticate(
        activity,
        title = activity.getString(R.string.link_confirm_title),
        subtitle = activity.getString(R.string.link_confirm_subtitle),
    )

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
