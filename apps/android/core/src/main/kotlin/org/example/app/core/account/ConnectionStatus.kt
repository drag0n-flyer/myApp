package org.example.app.core.account

/** What the person is told about the connection: the chat list strip and Settings > Server show it. */
enum class ConnectionStatus {
    Connected, Connecting, Offline;

    companion object {
        /** No network is offline; a network without the socket is still connecting (or waiting to retry). */
        fun from(socketConnected: Boolean, hasNetwork: Boolean): ConnectionStatus = when {
            !hasNetwork -> Offline
            socketConnected -> Connected
            else -> Connecting
        }
    }
}
