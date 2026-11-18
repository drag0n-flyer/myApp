package org.example.app.core.chats

/**
 * The rules of the hold-to-record gesture as one pure function. The screen turns touches into [Event]s and acts on the
 * [State] that comes back; nothing here touches a clock, a recorder or a file. Offsets are in dp from where the finger
 * went down. In a right-to-left layout the cancel direction mirrors (dx positive).
 */
object VoiceGesture {
    @Suppress("EnumEntryName")
    enum class State { idle, pending, recording, willCancel, willLock, locked, preview, cancelled, send, tooShort }

    @Suppress("ClassName")
    sealed interface Event {
        data object press : Event
        data class heldFor(val ms: Long) : Event
        data class drag(val dx: Float, val dy: Float) : Event
        data class release(val elapsedMs: Long) : Event
        data object stop : Event
        data object limitReached : Event
        data object interrupted : Event
        data object delete : Event
        data object sendTapped : Event
        data object activateAccessible : Event
    }

    const val startDelayMs = 150L
    const val minimumMs = 1000L
    const val cancelDistance = 100f
    const val lockDistance = 70f

    /** A recording is running in these states (the finger may be down or up). */
    fun isRecording(state: State) =
        state == State.recording || state == State.willCancel || state == State.willLock || state == State.locked

    fun step(state: State, event: Event, rtl: Boolean): State {
        val held = state == State.recording || state == State.willCancel || state == State.willLock
        return when (event) {
            Event.press -> if (isResting(state)) State.pending else state
            Event.activateAccessible -> if (isResting(state)) State.locked else state
            is Event.heldFor -> if (state == State.pending && event.ms >= startDelayMs) State.recording else state
            is Event.drag -> if (held) {
                val across = if (rtl) event.dx else -event.dx
                when {
                    across >= cancelDistance -> State.willCancel
                    event.dy <= -lockDistance -> State.willLock
                    else -> State.recording
                }
            } else state
            is Event.release -> when (state) {
                State.pending -> State.tooShort
                State.willCancel -> State.cancelled
                State.willLock -> State.locked
                State.recording -> if (event.elapsedMs >= minimumMs) State.send else State.tooShort
                else -> state
            }
            Event.stop -> if (state == State.locked) State.preview else state
            Event.limitReached -> if (isRecording(state)) State.preview else state
            Event.interrupted -> when {
                isRecording(state) -> State.preview
                state == State.pending -> State.idle
                else -> state
            }
            Event.delete -> if (state == State.locked || state == State.preview) State.cancelled else state
            Event.sendTapped -> if (state == State.locked || state == State.preview) State.send else state
        }
    }

    private fun isResting(state: State) =
        state == State.idle || state == State.cancelled || state == State.send || state == State.tooShort
}
