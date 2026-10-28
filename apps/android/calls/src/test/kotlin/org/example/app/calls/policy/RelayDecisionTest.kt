package org.example.app.calls.policy

import kotlin.test.Test
import kotlin.test.assertEquals

class RelayDecisionTest {
    @Test fun nonContactsRelaysStrangersOnly() {
        assertEquals(IceTransportPolicy.All, RelayDecision.policy(IPHiding.NonContacts, isContact = true))
        assertEquals(IceTransportPolicy.Relay, RelayDecision.policy(IPHiding.NonContacts, isContact = false))
    }

    @Test fun neverAndAlways() {
        for (c in listOf(true, false)) {
            assertEquals(IceTransportPolicy.All, RelayDecision.policy(IPHiding.Never, c))
            assertEquals(IceTransportPolicy.Relay, RelayDecision.policy(IPHiding.Always, c))
        }
    }
}
