package org.example.app.messaging.sync

import org.example.app.messaging.testing.FakeDeliveryServer

/** Devices of several accounts on one fake server. A device ID starting with `none` uploads no KeyPackage. */
class GroupWorld(devices: Map<String, List<String>>) {
    val server = FakeDeliveryServer()
    val all = devices.flatMap { (account, ids) -> ids.map { TestDevice(server, account, it) } }
    fun device(id: String) = all.first { it.me.deviceId == id }

    suspend fun start() = apply {
        for (d in all) d.api.uploadKeyPackages(if (d.me.deviceId.startsWith("none")) emptyList() else d.mls.makeKeyPackages(3))
    }

    companion object {
        /** ana (one device) with bob, cat and dan (two devices each). */
        suspend fun trio() = GroupWorld(
            mapOf("acc-ana" to listOf("ana1"), "acc-bob" to listOf("bob1", "bob2"), "acc-cat" to listOf("cat1", "cat2"), "acc-dan" to listOf("dan1", "dan2")),
        ).start()
    }
}
