package org.example.app.calls.policy

object RelayDecision {
    /** Each side applies its own setting: hiding protects my address. */
    fun policy(setting: IPHiding, isContact: Boolean): IceTransportPolicy =
        if (setting.requiresRelay(isContact)) IceTransportPolicy.Relay else IceTransportPolicy.All
}
