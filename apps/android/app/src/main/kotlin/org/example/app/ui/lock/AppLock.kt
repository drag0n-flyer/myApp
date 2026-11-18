package org.example.app.ui.lock

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.example.app.core.lock.AppLockPolicy
import org.example.app.core.settings.UserSettings

/**
 * The app lock's state for the whole process (it lives in the graph, so rotating or recreating the activity does not
 * unlock). [AppLockPolicy] decides; this reads the settings, a monotonic [clock] and publishes [locked].
 *
 * Starts locked when the setting is on, so a restarted process is locked. Holds no secret: the setting and the delay are
 * the only things stored (in the settings), and the only way to [unlock] is the phone's own prompt, which the callers
 * run first.
 */
class AppLock(
    private val settings: () -> UserSettings,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val policy = settings().let { AppLockPolicy(AppLockPolicy.validDelay(it.lockAfterSeconds), enabled = it.appLock) }
    private val state = MutableStateFlow(policy.locked)
    private var promptsAnswered = 0
    private var left = false

    /** True while the cover must show. The caller also checks the setting: a signed-out account has none. */
    val locked: StateFlow<Boolean> = state.asStateFlow()

    private val _promptRequests = MutableStateFlow(if (policy.locked) 1 else 0)

    /** Counts the times the cover should open the prompt by itself: at start, and on each return while locked. */
    val promptRequests: StateFlow<Int> = _promptRequests.asStateFlow()

    /** The activity became visible. */
    fun onStart() {
        sync()
        val returned = left
        left = false
        val lockedNow = policy.onForeground(clock())
        if (returned && lockedNow) request()
        state.value = policy.locked
    }

    /** The activity left the screen for good (not for a rotation: then there is nothing to measure). */
    fun onStop() {
        sync()
        left = true
        policy.onBackground(clock())
        state.value = policy.locked
    }

    /** The phone's prompt succeeded. */
    fun unlock() {
        policy.unlocked()
        state.value = false
    }

    /** The setting changed after a passed prompt (switched on) or a passed prompt to switch off. */
    fun setEnabled(on: Boolean) {
        policy.setEnabled(on)
        state.value = false
    }

    /** Whether the prompt for the current request has not been shown yet; true once per request. */
    @Synchronized
    fun takePrompt(): Boolean {
        val requested = _promptRequests.value
        if (requested == promptsAnswered) return false
        promptsAnswered = requested
        return policy.locked
    }

    private fun request() { _promptRequests.value += 1 }

    private fun sync() {
        policy.lockAfterSeconds = AppLockPolicy.validDelay(settings().lockAfterSeconds)
    }
}
