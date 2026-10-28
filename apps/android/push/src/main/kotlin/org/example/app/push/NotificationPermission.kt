package org.example.app.push

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 13+ asks for notifications at run time. The app asks once, right after sign-up, when the user has
 * just seen why messages matter; the sign-up screen launches the system prompt when [shouldAsk] is true and
 * then calls [markAsked].
 */
object NotificationPermission {
    private const val PREFS = "push_permission"
    private const val ASKED = "asked"
    private const val PERMISSION = "android.permission.POST_NOTIFICATIONS"

    fun shouldAsk(context: Context, signedUp: Boolean, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (!signedUp || sdkInt < 33) return false
        if (ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED) return false
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)
    }

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
    }
}
