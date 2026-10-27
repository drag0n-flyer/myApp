package org.example.app.calls.policy

enum class ReconnectAction { ShowReconnecting, RestartIce, RequestRestart, Recovered, GiveUp }

/**
 * Decides when to restart ICE and when to give up. Time (milliseconds) is passed in, so it is deterministic.
 * The caller restarts ICE; the callee asks for a restart.
 */
class ReconnectPolicy(private val isCaller: Boolean) {
    companion object {
        const val RESTART_AFTER_MS = 2_500L
        const val RETRY_EVERY_MS = 5_000L
        const val GIVE_UP_AFTER_MS = 30_000L
    }

    private var disconnectedSince: Long? = null
    private var lastRestart: Long? = null
    private var gaveUp = false
    private var closed = false

    private val restart get() = if (isCaller) ReconnectAction.RestartIce else ReconnectAction.RequestRestart

    fun ice(s: IceState, atMs: Long): List<ReconnectAction> {
        if (gaveUp || closed) return emptyList()
        when (s) {
            IceState.Disconnected, IceState.Failed -> {
                val out = mutableListOf<ReconnectAction>()
                if (disconnectedSince == null) {
                    disconnectedSince = atMs
                    lastRestart = null
                    out += ReconnectAction.ShowReconnecting
                }
                val last = lastRestart
                if (s == IceState.Failed && (last == null || atMs - last >= RETRY_EVERY_MS)) {
                    lastRestart = atMs
                    out += restart
                }
                return out
            }
            IceState.Connected, IceState.Completed -> {
                if (disconnectedSince == null) return emptyList()
                disconnectedSince = null
                lastRestart = null
                return listOf(ReconnectAction.Recovered)
            }
            IceState.Closed -> {
                closed = true
                disconnectedSince = null
                lastRestart = null
                return emptyList()
            }
            IceState.New, IceState.Checking -> return emptyList()
        }
    }

    fun pathChanged(atMs: Long): List<ReconnectAction> {
        if (gaveUp || closed) return emptyList()
        if (disconnectedSince != null) lastRestart = atMs
        return listOf(restart)
    }

    fun tick(atMs: Long): List<ReconnectAction> {
        val since = disconnectedSince
        if (gaveUp || closed || since == null) return emptyList()
        if (atMs - since >= GIVE_UP_AFTER_MS) {
            gaveUp = true
            return listOf(ReconnectAction.GiveUp)
        }
        val last = lastRestart
        val due = if (last != null) atMs - last >= RETRY_EVERY_MS else atMs - since >= RESTART_AFTER_MS
        if (!due) return emptyList()
        lastRestart = atMs
        return listOf(restart)
    }
}
