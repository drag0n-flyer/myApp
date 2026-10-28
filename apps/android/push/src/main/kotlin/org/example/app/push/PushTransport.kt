package org.example.app.push

import android.content.Context
import android.content.pm.PackageManager

enum class PushTransport {
    Fcm,
    UnifiedPush,
    None,
    ;

    companion object {
        /**
         * FCM needs Play services and a Firebase configuration. Without them, UnifiedPush is used when a
         * distributor is already chosen or exactly one is installed. With several and none chosen, the
         * user must pick one first, so the answer is [None].
         */
        fun choose(
            playServices: Boolean,
            distributorCount: Int,
            distributorChosen: Boolean = false,
            fcmConfigured: Boolean = true,
        ): PushTransport = when {
            playServices && fcmConfigured -> Fcm
            distributorCount > 0 && (distributorChosen || distributorCount == 1) -> UnifiedPush
            else -> None
        }
    }
}

object PlayServices {
    private const val PACKAGE = "com.google.android.gms"

    /** True when Google Play services is installed and enabled. */
    fun available(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo(PACKAGE, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
