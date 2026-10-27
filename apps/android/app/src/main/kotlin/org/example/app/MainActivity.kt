package org.example.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
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
