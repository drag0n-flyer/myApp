package org.example.app.calls.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.core.calls.NetworkType

class NetworkWatcherTest {
    private class FakeSource : NetworkSource {
        var report: ((NetworkType) -> Unit)? = null
        var registers = 0
        var unregisters = 0
        override fun register(report: (NetworkType) -> Unit) { registers++; this.report = report }
        override fun unregister() { unregisters++; report = null }
        fun emit(t: NetworkType) { report?.invoke(t) }
    }

    private val source = FakeSource()
    private val seen = mutableListOf<NetworkType>()
    private val watcher = NetworkWatcher(source) { seen += it }

    @Test
    fun wifiThenMobileReportsTwoChanges() {
        watcher.start()
        source.emit(NetworkType.Wifi)
        source.emit(NetworkType.Mobile)
        assertEquals(listOf(NetworkType.Wifi, NetworkType.Mobile), seen)
    }

    @Test
    fun aBurstOfTheSameNetworkReportsOnce() {
        watcher.start()
        repeat(20) { source.emit(NetworkType.Wifi) }
        assertEquals(listOf(NetworkType.Wifi), seen)
    }

    @Test
    fun goingBackToAnEarlierNetworkIsAChange() {
        watcher.start()
        source.emit(NetworkType.Wifi)
        source.emit(NetworkType.Mobile)
        source.emit(NetworkType.Wifi)
        assertEquals(3, seen.size)
    }

    @Test
    fun stopUnregistersAndSilencesLateCallbacks() {
        watcher.start()
        val late = source.report!!
        watcher.stop()
        assertEquals(1, source.unregisters)
        late(NetworkType.Mobile)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun startAndStopAreIdempotent() {
        watcher.start()
        watcher.start()
        assertEquals(1, source.registers)
        watcher.stop()
        watcher.stop()
        assertEquals(1, source.unregisters)
    }

    @Test
    fun aRestartForTheNextCallReportsTheCurrentNetworkAgain() {
        watcher.start()
        source.emit(NetworkType.Wifi)
        watcher.stop()
        watcher.start()
        source.emit(NetworkType.Wifi)
        assertEquals(2, seen.size)
    }

    @Test
    fun classifiesTransports() {
        assertEquals(NetworkType.Wifi, NetworkClassifier.kind(wifi = true, ethernet = false, cellular = false, notMetered = true))
        assertEquals(NetworkType.Wifi, NetworkClassifier.kind(wifi = false, ethernet = true, cellular = false, notMetered = true))
        assertEquals(NetworkType.Mobile, NetworkClassifier.kind(wifi = false, ethernet = false, cellular = true, notMetered = false))
        // A VPN with no known transport: metered means mobile data.
        assertEquals(NetworkType.Mobile, NetworkClassifier.kind(wifi = false, ethernet = false, cellular = false, notMetered = false))
        // Wi-Fi wins when a phone reports both.
        assertEquals(NetworkType.Wifi, NetworkClassifier.kind(wifi = true, ethernet = false, cellular = true, notMetered = true))
    }
}
