package org.example.app.core.people

/**
 * When to send a typing signal (design 6.1): `on: true` when composing starts and every 8,000 ms while it goes on,
 * `on: false` when the message is sent, the text becomes empty, or 5,000 ms pass without a keystroke.
 */
object TypingSender {
    const val REPEAT_MS = 8_000L
    const val IDLE_MS = 5_000L

    /** [typing]: an `on: true` is out and no `on: false` has followed. */
    data class State(val typing: Boolean = false, val lastSentMs: Long = 0, val lastKeyMs: Long = 0)

    sealed interface Event {
        data class TextChanged(val isEmpty: Boolean) : Event
        data object Sent : Event
        data object Tick : Event
    }

    /** [send]: true = send `on: true`, false = send `on: false`, null = send nothing. */
    data class Step(val state: State, val send: Boolean?)

    fun step(state: State, event: Event, nowMs: Long): Step = when (event) {
        is Event.TextChanged -> if (event.isEmpty) off(state) else keystroke(state, nowMs)
        Event.Sent -> off(state)
        Event.Tick -> tick(state, nowMs)
    }

    private fun off(state: State) = if (state.typing) Step(state.copy(typing = false), false) else Step(state, null)

    private fun keystroke(state: State, nowMs: Long): Step = when {
        !state.typing -> Step(State(typing = true, lastSentMs = nowMs, lastKeyMs = nowMs), true)
        nowMs - state.lastSentMs >= REPEAT_MS -> Step(state.copy(lastSentMs = nowMs, lastKeyMs = nowMs), true)
        else -> Step(state.copy(lastKeyMs = nowMs), null)
    }

    private fun tick(state: State, nowMs: Long): Step = when {
        !state.typing -> Step(state, null)
        nowMs - state.lastKeyMs >= IDLE_MS -> Step(state.copy(typing = false), false)
        nowMs - state.lastSentMs >= REPEAT_MS -> Step(state.copy(lastSentMs = nowMs), true)
        else -> Step(state, null)
    }
}

/** Whether to show "typing…": for 10,000 ms after the last `on: true`; never when the clock went back (a negative age). The caller forgets the time on `on: false` or on an incoming message. */
object TypingShown {
    const val SHOWN_MS = 10_000L

    fun isTyping(lastOnMs: Long?, nowMs: Long): Boolean = lastOnMs != null && nowMs >= lastOnMs && nowMs - lastOnMs < SHOWN_MS
}

/**
 * The line under a contact's name: "online" for 90,000 ms after the last `online` signal, else "last seen" at the time
 * this device last witnessed the contact (a signal or a message), else nothing. The caller forgets the last `online`
 * when `away` arrives, so `away` ends "online" at once.
 */
object PresenceLine {
    const val ONLINE_MS = 90_000L

    sealed interface Result {
        data object Online : Result
        data class LastSeen(val atMs: Long) : Result
        data object None : Result
    }

    fun text(lastOnlineMs: Long?, lastWitnessedMs: Long?, nowMs: Long): Result = when {
        lastOnlineMs != null && nowMs >= lastOnlineMs && nowMs - lastOnlineMs < ONLINE_MS -> Result.Online
        lastWitnessedMs != null -> Result.LastSeen(lastWitnessedMs)
        else -> Result.None
    }
}
