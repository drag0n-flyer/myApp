package org.example.app.calls.android

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.example.app.core.calls.NetworkType

/** Where [NetworkWatcher] gets raw network reports from. The system one is [SystemNetworkSource]; tests use a fake. */
internal interface NetworkSource {
    fun register(report: (NetworkType) -> Unit)
    fun unregister()
}

/**
 * Tells [onChange] which kind of network the device is on, while started. The same kind twice in a row is reported
 * once, so a burst of identical system callbacks cannot restart a call's media again and again. The engine's existing
 * network type ([NetworkType]) is the vocabulary; its `setNetwork` is the usual receiver.
 *
 * Start it when a call starts and stop it when the call ends. Each start reports the current network once.
 */
class NetworkWatcher internal constructor(
    private val source: NetworkSource,
    private val onChange: (NetworkType) -> Unit,
) {
    constructor(connectivity: ConnectivityManager, onChange: (NetworkType) -> Unit) :
        this(SystemNetworkSource(connectivity), onChange)

    private val lock = Any()
    private var started = false
    private var last: NetworkType? = null

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            last = null
        }
        source.register(::report)
    }

    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            last = null
        }
        source.unregister()
    }

    private fun report(type: NetworkType) {
        synchronized(lock) {
            if (!started || type == last) return
            last = type
        }
        onChange(type)
    }
}

/** Which [NetworkType] a network's transports mean. */
internal object NetworkClassifier {
    fun kind(wifi: Boolean, ethernet: Boolean, cellular: Boolean, notMetered: Boolean): NetworkType = when {
        wifi || ethernet -> NetworkType.Wifi
        cellular -> NetworkType.Mobile
        // A VPN or other transport: an unmetered one is treated like Wi-Fi.
        notMetered -> NetworkType.Wifi
        else -> NetworkType.Mobile
    }
}

/** The default-network callback of [ConnectivityManager] (needs only ACCESS_NETWORK_STATE). */
internal class SystemNetworkSource(private val connectivity: ConnectivityManager) : NetworkSource {
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    override fun register(report: (NetworkType) -> Unit) {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                current = network
                report(
                    NetworkClassifier.kind(
                        wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                        ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                        cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                        notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    ),
                )
            }

            override fun onLost(network: Network) {
                // Only the network in use going away is "offline"; an old network lost after a switch is not.
                if (network == current) {
                    current = null
                    report(NetworkType.Offline)
                }
            }
        }
        callback = cb
        connectivity.registerDefaultNetworkCallback(cb)
    }

    @Synchronized
    override fun unregister() {
        val cb = callback ?: return
        callback = null
        try {
            connectivity.unregisterNetworkCallback(cb)
        } catch (_: IllegalArgumentException) {
            // Already gone.
        }
    }
}
