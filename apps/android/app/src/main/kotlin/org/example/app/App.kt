package org.example.app

import android.app.Application
import org.example.app.live.PushWiring

/**
 * Holds the live graph for the whole process, so a push that starts a killed process reaches the same services as
 * the UI. Nothing heavy runs here: the graph, the Keystore and the native libraries load on first use.
 */
class App : Application() {
    val graph: AppGraph by lazy { AppGraph(this, sampleData = false) }

    override fun onCreate() {
        super.onCreate()
        PushWiring.install(this) { graph }
    }
}
