package org.example.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.example.app.push.MessageNotifier

/** Reopened from Recents: the system hands back the intent that first started the task, which was already acted on. */
private fun fromHistory(intent: Intent?) = intent != null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0

/** The chat a message notification opens: its `EXTRA_CHAT_ID`, or null for no intent, no extra or a blank one. */
fun chatIdFrom(intent: Intent?): String? =
    if (fromHistory(intent)) null else intent?.getStringExtra(MessageNotifier.EXTRA_CHAT_ID)?.takeIf { it.isNotBlank() }

/** The link an app link (`https://<host>/u/...` or `/c/...`) opened this activity with, or null. Anything else is not for the link handler. */
fun contactLinkFrom(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_VIEW || fromHistory(intent)) return null
    val data = intent.data ?: return null
    return if (data.scheme == "https") data.toString() else null
}

// FragmentActivity (a ComponentActivity) because androidx.biometric's prompt needs one.
class MainActivity : FragmentActivity() {
    private lateinit var graph: AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The live services by default; UI tests pass `sampleData` for the sample ones.
        graph = if (usesSampleData(intent)) AppGraph(this, sampleData = true) else (application as App).graph
        graph.activity = this
        // UI tests on sample data can start with the lock on (the sample has no real data to protect).
        if (graph.sampleData && intent.getBooleanExtra(EXTRA_APP_LOCK, false)) graph.settingsStore.update { it.copy(appLock = true) }
        keepWindowSecureWhileLockIsOn()
        // Held while locked: AppRoot follows it once after the unlock.
        chatIdFrom(intent)?.let { graph.requestedChat.value = it }
        // A recreation (rotation) gets the same intent again: that link was already taken.
        if (savedInstanceState == null) contactLinkFrom(intent)?.let { graph.pendingLink.offer(it) }
        setContent { AppRoot(graph) }
    }

    /** Recents and screenshots show nothing while app lock is on: FLAG_SECURE, set before the first frame and kept in step. */
    private fun keepWindowSecureWhileLockIsOn() {
        fun apply(on: Boolean) {
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        apply(graph.appLockOn)
        lifecycleScope.launch { graph.settingsStore.settings.map { it.appLock }.distinctUntilChanged().collect(::apply) }
    }

    /** The app was already running when a notification was tapped. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        chatIdFrom(intent)?.let { graph.requestedChat.value = it }
        contactLinkFrom(intent)?.let { graph.pendingLink.offer(it) }
    }

    override fun onStart() {
        super.onStart()
        graph.appLock.onStart()
        graph.setVisible(true)
    }

    override fun onStop() {
        graph.setVisible(false)
        // A rotation or other recreation is not leaving the app: the lock neither opens nor starts its timer.
        if (!isChangingConfigurations) graph.appLock.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        if (graph.activity === this) graph.activity = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SAMPLE_DATA = "sampleData"

        /** With sample data: start with app lock on (UI tests). */
        const val EXTRA_APP_LOCK = "appLock"

        /** The live services unless the launch asks for sample data (UI tests). */
        fun usesSampleData(intent: android.content.Intent): Boolean = intent.getBooleanExtra(EXTRA_SAMPLE_DATA, false)
    }
}
