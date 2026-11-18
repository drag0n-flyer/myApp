package org.example.app.core.lock

/**
 * When the app asks for the person's fingerprint, face or screen lock again (design 6.3). Pure: the caller passes a
 * monotonic clock reading in milliseconds (`SystemClock.elapsedRealtime`) with every call. It starts locked when
 * [enabled], so a restarted process is locked. Main thread only.
 *
 * A clock that reads earlier on return than on leaving (a reboot, or a clock that was set back) cannot say how long the
 * app was away, so it locks.
 */
class AppLockPolicy(var lockAfterSeconds: Int, enabled: Boolean = true) {
    var enabled: Boolean = enabled
        private set

    /** True until the device authentication succeeds ([unlocked]). Always false when not [enabled]. */
    var locked: Boolean = enabled
        private set

    private var leftAtMs: Long? = null

    /** The app left the screen. With "Immediately" the lock is set now, so nothing waits behind the screen on return. */
    fun onBackground(nowMs: Long) {
        if (!enabled) return
        if (leftAtMs == null) leftAtMs = nowMs
        if (lockAfterSeconds <= 0) locked = true
    }

    /** The app came back. Returns whether it is locked. Without a matching [onBackground] (a rotation) nothing changes. */
    fun onForeground(nowMs: Long): Boolean {
        val left = leftAtMs
        leftAtMs = null
        if (enabled && left != null) {
            val away = nowMs - left
            if (away < 0 || away >= lockAfterSeconds * 1000L) locked = true
        }
        return locked
    }

    /** The person passed the device authentication. */
    fun unlocked() {
        locked = false
        leftAtMs = null
    }

    /** The setting changed. Turning it on happens after a passed prompt, so the app stays unlocked; off unlocks. */
    fun setEnabled(on: Boolean) {
        enabled = on
        locked = false
        leftAtMs = null
    }

    companion object {
        const val DEFAULT_SECONDS = 60

        /** Immediately, 1 minute, 5 minutes, 1 hour. */
        val DELAYS: List<Int> = listOf(0, 60, 300, 3600)

        /** [seconds] if it is one of [DELAYS], else the default: stored data from another version must not disable the lock. */
        fun validDelay(seconds: Int): Int = if (seconds in DELAYS) seconds else DEFAULT_SECONDS
    }
}
