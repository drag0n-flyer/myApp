package org.example.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.example.app.contracts.MemorySecretStore
import org.example.app.core.account.ServerAddress

/** The app builds its clients from a server address (task I1). */
class ServerClientAddressTest {
    private val sessions = SessionStore(MemorySecretStore())

    @Test
    fun addressClientKeepsSchemeAndPort() {
        assertEquals("https://chat.example.org/", ServerClient(ServerAddress("chat.example.org", null, true), sessions).baseUrl.toString())
        assertEquals("http://localhost:8080/", ServerClient(ServerAddress("localhost", 8080, false), sessions).baseUrl.toString())
    }

    @Test
    fun addressClientRefusesPlainHttpOffLoopback() {
        assertFailsWith<IllegalArgumentException> { ServerClient(ServerAddress("10.0.2.2", 8080, false), sessions) }
    }
}
