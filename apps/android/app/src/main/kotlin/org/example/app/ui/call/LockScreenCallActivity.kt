package org.example.app.ui.call

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.example.app.MainActivity
import org.example.app.calls.android.CallExtras
import org.example.app.calls.android.CallInfo
import org.example.app.calls.android.CallsRuntime
import org.example.app.core.calls.CallPhase
import org.example.app.ui.theme.AppTheme

/**
 * The ringing call over the lock screen, opened by the call notification's full-screen intent: the Android form of
 * the iPhone's CallKit screen (design §8.3). Answer opens the app's call screen; Decline ends the call. It closes
 * itself once the call stops ringing (answered elsewhere, cancelled, missed).
 */
class LockScreenCallActivity : ComponentActivity() {
    private var call: CallInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val info = CallExtras.read(intent?.extras)
        if (info == null) {
            finish()
            return
        }
        call = info
        val name = CallExtras.displayName(this, info)
        setContent {
            AppTheme {
                IncomingCallContent(name = name, onDecline = ::decline, onAnswer = ::answer)
            }
        }
        watchRinging()
    }

    private fun answer() {
        val info = call ?: return
        CallsRuntime.engine?.answer(info.video)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(CallExtras.CALL_ID, info.callId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    private fun decline() {
        CallsRuntime.engine?.decline()
        finish()
    }

    /**
     * Only a call that rings now has anything to answer: one that already ended (or none at all) closes the screen at
     * once, and so does a ringing call once it stops ringing.
     */
    private fun watchRinging() {
        val engine = CallsRuntime.engine
        if (engine?.state?.value?.phase != CallPhase.Incoming) {
            finish()
            return
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.state.collect { state -> if (state?.phase != CallPhase.Incoming) finish() }
            }
        }
    }
}
