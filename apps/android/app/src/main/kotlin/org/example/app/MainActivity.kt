package org.example.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import org.example.app.push.MessageNotifier

/** The chat a message notification opens: its `EXTRA_CHAT_ID`, or null for no intent, no extra or a blank one. */
fun chatIdFrom(intent: Intent?): String? =
    intent?.getStringExtra(MessageNotifier.EXTRA_CHAT_ID)?.takeIf { it.isNotBlank() }

// FragmentActivity (a ComponentActivity) because androidx.biometric's prompt needs one.
class MainActivity : FragmentActivity() {
    private lateinit var graph: AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The live services by default; UI tests pass `sampleData` for the sample ones.
        graph = if (usesSampleData(intent)) AppGraph(this, sampleData = true) else (application as App).graph
        graph.activity = this
        chatIdFrom(intent)?.let { graph.requestedChat.value = it }
        setContent { AppRoot(graph) }
    }

    /** The app was already running when a notification was tapped. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        chatIdFrom(intent)?.let { graph.requestedChat.value = it }
    }

    override fun onStart() {
        super.onStart()
        graph.setVisible(true)
    }

    override fun onStop() {
        graph.setVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        if (graph.activity === this) graph.activity = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SAMPLE_DATA = "sampleData"

        /** The live services unless the launch asks for sample data (UI tests). */
        fun usesSampleData(intent: android.content.Intent): Boolean = intent.getBooleanExtra(EXTRA_SAMPLE_DATA, false)
    }
}
