package org.example.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity

// FragmentActivity (a ComponentActivity) because androidx.biometric's prompt needs one.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Default true until plan Task I1 adds the live services.
        val graph = AppGraph(this, sampleData = intent.getBooleanExtra(EXTRA_SAMPLE_DATA, true))
        setContent { AppRoot(graph) }
    }

    companion object {
        const val EXTRA_SAMPLE_DATA = "sampleData"
    }
}
