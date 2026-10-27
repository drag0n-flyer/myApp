package org.example.app.net.api

import org.example.app.net.Auth
import org.example.app.net.ServerClient
import org.example.app.net.models.ServerInfo

class ServerInfoApi(private val client: ServerClient) {
    suspend fun serverInfo(): ServerInfo = client.request("GET", "/v1/server", null, Auth.None, ServerInfo.serializer())
}

enum class ProtocolResult { Supported, UpdateRequired }

object ProtocolCheck {
    const val MAX_PROTOCOL = 1

    fun check(info: ServerInfo): ProtocolResult =
        if (info.protocol > MAX_PROTOCOL) ProtocolResult.UpdateRequired else ProtocolResult.Supported
}
