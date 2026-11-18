package org.example.app.ui.lock

/**
 * App lock's on/off as a plain in-memory read for code that must not block (the notifications read it while their lock
 * is held). Until the stored setting has been loaded it reads ON: notifications then hide their content rather than
 * show it. The settings store sets it whenever it loads, changes or resets; a process that no screen ever started
 * (a push that woke it) loads it with [ensureLoaded] before the first notification is posted.
 */
class LockSwitch {
    @Volatile private var value: Boolean? = null

    /** The setting, or true while it is not known yet. */
    val on: Boolean get() = value ?: true

    val loaded: Boolean get() = value != null

    fun set(on: Boolean) { value = on }

    /** Reads the stored setting with [read] if nothing has set it yet. May block (a disk read): call it off the main thread and outside any lock. */
    fun ensureLoaded(read: () -> Boolean) {
        if (value != null) return
        synchronized(this) {
            if (value == null) value = try { read() } catch (e: Exception) { true }
        }
    }

    companion object {
        /** The one the live app uses: written by the live `SettingsStore`, read by `PushWiring`. */
        val shared = LockSwitch()
    }
}
