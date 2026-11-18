package org.example.app.core.account

import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionStatusTest {
    @Test fun socketAndNetworkIsConnected() =
        assertEquals(ConnectionStatus.Connected, ConnectionStatus.from(socketConnected = true, hasNetwork = true))

    @Test fun networkWithoutSocketIsConnecting() =
        assertEquals(ConnectionStatus.Connecting, ConnectionStatus.from(socketConnected = false, hasNetwork = true))

    @Test fun noNetworkIsOffline() =
        assertEquals(ConnectionStatus.Offline, ConnectionStatus.from(socketConnected = false, hasNetwork = false))

    @Test fun noNetworkWinsOverASocketThatHasNotNoticedYet() =
        assertEquals(ConnectionStatus.Offline, ConnectionStatus.from(socketConnected = true, hasNetwork = false))
}
