package org.example.app.calls.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.app.calls.policy.ReconnectAction as A

private fun at(s: Double): Long = 1_000_000L + (s * 1000).toLong()

class ReconnectPolicyTest {
    private val t0 = at(0.0)

    @Test fun restartAfter2500ms() {
        val p = ReconnectPolicy(isCaller = true)
        assertEquals(listOf(A.ShowReconnecting), p.ice(IceState.Disconnected, t0))
        assertEquals(emptyList(), p.ice(IceState.Disconnected, at(1.0)))
        assertEquals(emptyList(), p.tick(at(2.4)))
        assertEquals(listOf(A.RestartIce), p.tick(at(2.5)))
        assertEquals(emptyList(), p.tick(at(5.0)))
        assertEquals(listOf(A.RestartIce), p.tick(at(7.5)))
        assertEquals(listOf(A.Recovered), p.ice(IceState.Connected, at(8.0)))
        assertEquals(emptyList(), p.tick(at(20.0)))
        assertEquals(emptyList(), p.ice(IceState.Connected, at(21.0)))
    }

    @Test fun givesUpAt30s() {
        val p = ReconnectPolicy(isCaller = true)
        p.ice(IceState.Disconnected, t0)
        assertEquals(listOf(A.GiveUp), p.tick(at(30.0)))
        assertEquals(emptyList(), p.tick(at(31.0)))
        assertEquals(emptyList(), p.tick(at(40.0)))
    }

    @Test fun failedRestartsImmediately() {
        val p = ReconnectPolicy(isCaller = true)
        assertEquals(listOf(A.ShowReconnecting, A.RestartIce), p.ice(IceState.Failed, t0))
        assertEquals(emptyList(), p.tick(at(2.5)))
        assertEquals(listOf(A.RestartIce), p.tick(at(5.0)))
    }

    @Test fun calleeRequestsRestart() {
        val p = ReconnectPolicy(isCaller = false)
        assertEquals(listOf(A.ShowReconnecting), p.ice(IceState.Disconnected, t0))
        assertEquals(emptyList(), p.tick(at(2.4)))
        assertEquals(listOf(A.RequestRestart), p.tick(at(2.5)))
        assertEquals(listOf(A.RequestRestart), p.tick(at(7.5)))
        assertEquals(listOf(A.Recovered), p.ice(IceState.Connected, at(8.0)))
        val q = ReconnectPolicy(isCaller = false)
        assertEquals(listOf(A.ShowReconnecting, A.RequestRestart), q.ice(IceState.Failed, t0))
    }

    @Test fun pathChangeRestarts() {
        val p = ReconnectPolicy(isCaller = true)
        assertEquals(emptyList(), p.ice(IceState.Connected, t0))
        assertEquals(listOf(A.RestartIce), p.pathChanged(at(5.0)))
        assertEquals(listOf(A.RequestRestart), ReconnectPolicy(isCaller = false).pathChanged(at(5.0)))
    }

    @Test fun pathChangeWhileDisconnectedRestartsAndResetsRetry() {
        val p = ReconnectPolicy(isCaller = true)
        p.ice(IceState.Disconnected, t0)
        assertEquals(listOf(A.RestartIce), p.pathChanged(at(1.0)))
        assertEquals(emptyList(), p.tick(at(5.9)))
        assertEquals(listOf(A.RestartIce), p.tick(at(6.0)))
    }

    @Test fun giveUpIsFinal() {
        val p = ReconnectPolicy(isCaller = true)
        p.ice(IceState.Disconnected, t0)
        p.tick(at(30.0))
        assertEquals(emptyList(), p.pathChanged(at(31.0)))
        assertEquals(emptyList(), p.ice(IceState.Connected, at(32.0)))
    }

    @Test fun closedIsTerminal() {
        val p = ReconnectPolicy(isCaller = true)
        p.ice(IceState.Disconnected, t0)
        assertEquals(emptyList(), p.ice(IceState.Closed, at(1.0)))
        assertEquals(emptyList(), p.pathChanged(at(2.0)))
        assertEquals(emptyList(), p.tick(at(10.0)))
        assertEquals(emptyList(), p.ice(IceState.Disconnected, at(11.0)))
    }

    @Test fun constants() {
        assertEquals(2_500L, ReconnectPolicy.RESTART_AFTER_MS)
        assertEquals(5_000L, ReconnectPolicy.RETRY_EVERY_MS)
        assertEquals(30_000L, ReconnectPolicy.GIVE_UP_AFTER_MS)
    }
}
