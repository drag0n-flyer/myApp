package org.example.app.push

import android.content.Context

/**
 * Two measurements, kept on disk:
 * - high-priority pushes that showed nothing (FCM may demote an app whose high-priority pushes show nothing;
 *   the beta reads these counts), and
 * - inbox items that arrived with no push near them, which means pushes are being lost (a maker's battery
 *   manager stopped the app). One missed push turns on a one-time hint that opens the system battery page.
 *   The app never asks for the battery exemption.
 */
class MissedPushMonitor(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        private const val PREFS = "push_monitor"
        private const val HIGH = "high"
        private const val QUIET = "quiet"
        private const val TIMES = "times"
        private const val MISSED = "missed"
        private const val HINT_SHOWN = "hintShown"
        private const val KEEP = 50

        /** An item younger than this may still get its push. */
        const val GRACE_MS = 2 * 60_000L

        /** A push this close to an item's arrival counts as that item's push. */
        const val NEAR_MS = 2 * 60_000L
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun pushHandled(highPriority: Boolean, showedSomething: Boolean) {
        val edit = prefs.edit()
        if (highPriority) {
            edit.putInt(HIGH, prefs.getInt(HIGH, 0) + 1)
            if (!showedSomething) edit.putInt(QUIET, prefs.getInt(QUIET, 0) + 1)
        }
        edit.putString(TIMES, (times() + clock()).takeLast(KEEP).joinToString(","))
        edit.apply()
    }

    fun highPriorityCount(): Int = prefs.getInt(HIGH, 0)

    fun quietHighPriorityCount(): Int = prefs.getInt(QUIET, 0)

    /** Call for each inbox item the app finds that was not delivered over the live socket. */
    @Synchronized
    fun noteInboxItem(arrivedAtMs: Long) {
        if (clock() - arrivedAtMs < GRACE_MS) return
        if (times().any { it in (arrivedAtMs - NEAR_MS)..(arrivedAtMs + NEAR_MS) }) return
        prefs.edit().putInt(MISSED, prefs.getInt(MISSED, 0) + 1).apply()
    }

    fun missedCount(): Int = prefs.getInt(MISSED, 0)

    fun shouldShowBatteryHint(): Boolean = missedCount() > 0 && !prefs.getBoolean(HINT_SHOWN, false)

    fun markHintShown() {
        prefs.edit().putBoolean(HINT_SHOWN, true).apply()
    }

    private fun times(): List<Long> =
        prefs.getString(TIMES, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
}
