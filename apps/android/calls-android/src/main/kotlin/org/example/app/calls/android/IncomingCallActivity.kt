package org.example.app.calls.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * The target of the call notification actions. It passes Answer, Decline or Hang up to the engine, opens the app
 * on Answer and on [ACTION_OPEN], and finishes. It has no UI of its own; the full-screen intent goes to the app's
 * lock-screen call screen ([ACTION_SHOW]).
 */
class IncomingCallActivity : Activity() {
    companion object {
        /** The app's lock-screen call screen answers this action (see [CallNotifications.showScreen]). */
        const val ACTION_SHOW = "org.example.app.calls.SHOW"
        const val ACTION_OPEN = "org.example.app.calls.OPEN"
        const val ACTION_ANSWER = "org.example.app.calls.ANSWER"
        const val ACTION_DECLINE = "org.example.app.calls.DECLINE"
        const val ACTION_HANG_UP = "org.example.app.calls.HANG_UP"

        fun intent(context: Context, action: String, call: CallInfo): Intent =
            Intent(context, IncomingCallActivity::class.java)
                .setAction(action)
                .putExtras(CallExtras.bundle(call))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        val call = CallExtras.read(intent?.extras) ?: return
        val engine = CallsRuntime.engine
        when (intent?.action) {
            ACTION_ANSWER -> {
                engine?.answer(call.video)
                openApp(call)
            }
            ACTION_DECLINE -> engine?.decline()
            ACTION_HANG_UP -> engine?.hangUp()
            ACTION_OPEN -> openApp(call)
        }
    }

    private fun openApp(call: CallInfo) {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.putExtra(CallExtras.CALL_ID, call.callId)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(launch)
    }
}
