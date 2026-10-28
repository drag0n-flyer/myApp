package org.example.app.messaging.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.example.app.messaging.ports.Clock

class KeyPackagePoolTest {
    private class Setup(var now: Long = NOW) {
        val rig = Rig()
        val device = rig.ana1
        val pool = KeyPackagePool(device.mls, device.api, device.repo, device.lock, Clock { now })
        fun calls(name: String) = rig.server.calls.count { it == "acc-ana/ana1 $name" }
    }

    @Test
    fun refillOnlyBelow10() = runTest {
        val s = Setup()
        s.rig.start() // 3 KeyPackages per device
        assertEquals(3, s.device.api.keyPackageCount())
        s.pool.refillIfDue()
        assertEquals(53, s.device.api.keyPackageCount())
        assertEquals(1, s.calls("uploadKeyPackages") - 1) // the rig's own upload is the other one

        // Exactly 10 is enough.
        val t = Setup(); t.rig.start()
        t.device.api.uploadKeyPackages(t.device.mls.makeKeyPackages(7))
        val uploadsBefore = t.calls("uploadKeyPackages")
        t.pool.refillIfDue()
        assertEquals(uploadsBefore, t.calls("uploadKeyPackages"))
        assertEquals(10, t.device.api.keyPackageCount())

        // 9 is not.
        val u = Setup(); u.rig.start()
        u.device.api.uploadKeyPackages(u.device.mls.makeKeyPackages(6))
        u.pool.refillIfDue()
        assertEquals(59, u.device.api.keyPackageCount())
        s.rig.assertLockDiscipline()
    }

    @Test
    fun refillAtMost6Hourly() = runTest {
        val s = Setup()
        s.rig.start()
        s.pool.refillIfDue()
        assertEquals(1, s.calls("keyPackageCount"))
        s.pool.refillIfDue()
        s.now += 6 * 3600_000 - 1
        s.pool.refillIfDue()
        assertEquals(1, s.calls("keyPackageCount"), "no check inside 6 hours")
        s.now += 1
        s.pool.refillIfDue()
        assertEquals(2, s.calls("keyPackageCount"))
        assertEquals(1, s.calls("uploadKeyPackages") - 1, "the second check found 53")
    }

    @Test
    fun failedCheckIsRetriedNextTime() = runTest {
        val s = Setup()
        s.rig.start()
        s.rig.server.onCall = { identity, call ->
            if (call == "uploadKeyPackages" && identity == "acc-ana/ana1" && s.rig.server.calls.count { it == "acc-ana/ana1 uploadKeyPackages" } > 1) {
                throw org.example.app.contracts.ServerException(503, "unavailable", null, null)
            }
        }
        runCatching { s.pool.refillIfDue() }
        s.rig.server.onCall = null
        s.pool.refillIfDue()
        assertEquals(53, s.device.api.keyPackageCount())
    }
}
