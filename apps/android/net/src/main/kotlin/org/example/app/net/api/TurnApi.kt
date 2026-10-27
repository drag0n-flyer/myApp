package org.example.app.net.api

import org.example.app.net.Auth
import org.example.app.net.ServerClient
import org.example.app.net.models.TurnCredentials

class TurnApi(private val client: ServerClient) {
    /** Throws `calls_disabled` (503) when the server has no relay. */
    suspend fun turnCredentials(): TurnCredentials =
        client.request("GET", "/v1/turn", null, Auth.Session, TurnCredentials.serializer())
}
