package org.example.app.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Starts Firebase from options in `BuildConfig` (from the gitignored `apps/android/firebase.properties`),
 * not from google-services.json. With no options (debug builds, tests) nothing starts and FCM stays off.
 */
object FirebaseInit {
    class Options(val projectId: String, val appId: String, val apiKey: String, val senderId: String) {
        val complete: Boolean get() = projectId.isNotBlank() && appId.isNotBlank() && apiKey.isNotBlank() && senderId.isNotBlank()
    }

    fun buildConfigOptions() = Options(BuildConfig.FCM_PROJECT_ID, BuildConfig.FCM_APP_ID, BuildConfig.FCM_API_KEY, BuildConfig.FCM_SENDER_ID)

    /** Returns true when Firebase is running. Safe to call more than once. */
    fun init(context: Context, options: Options = buildConfigOptions()): Boolean {
        if (!options.complete) return false
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setProjectId(options.projectId)
                        .setApplicationId(options.appId)
                        .setApiKey(options.apiKey)
                        .setGcmSenderId(options.senderId)
                        .build(),
                )
            }
            true
        } catch (_: RuntimeException) {
            false
        }
    }
}
