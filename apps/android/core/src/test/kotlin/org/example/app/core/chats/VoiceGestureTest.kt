package org.example.app.core.chats

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.app.core.chats.VoiceGesture.Event
import org.example.app.core.chats.VoiceGesture.State

class VoiceGestureTest {
    private fun step(s: State, e: Event, rtl: Boolean = false) = VoiceGesture.step(s, e, rtl)

    @Test fun constantsAreTheSpecsValues() {
        assertEquals(150L, VoiceGesture.startDelayMs)
        assertEquals(1000L, VoiceGesture.minimumMs)
        assertEquals(100f, VoiceGesture.cancelDistance)
        assertEquals(70f, VoiceGesture.lockDistance)
    }

    @Test fun holdingStartsRecordingAfterTheDelay() {
        assertEquals(State.pending, step(State.idle, Event.press))
        assertEquals(State.pending, step(State.pending, Event.heldFor(149)))
        assertEquals(State.recording, step(State.pending, Event.heldFor(150)))
    }

    @Test fun releaseBeforeTheStartDelayIsTooShort() {
        assertEquals(State.tooShort, step(State.pending, Event.release(100)))
    }

    @Test fun releaseAtTheMinimumSendsAndJustBeforeDoesNot() {
        assertEquals(State.tooShort, step(State.recording, Event.release(999)))
        assertEquals(State.send, step(State.recording, Event.release(1000)))
    }

    @Test fun slidingToTheLeadingEdgeCancels() {
        assertEquals(State.willCancel, step(State.recording, Event.drag(-100f, 0f)))
        assertEquals(State.cancelled, step(State.willCancel, Event.release(5000)))
        assertEquals(State.recording, step(State.willCancel, Event.drag(-99f, 0f)))
    }

    @Test fun rightToLeftMirrorsTheCancelDirection() {
        assertEquals(State.willCancel, step(State.recording, Event.drag(100f, 0f), rtl = true))
        assertEquals(State.recording, step(State.recording, Event.drag(-100f, 0f), rtl = true))
        assertEquals(State.recording, step(State.recording, Event.drag(99f, 0f), rtl = true))
    }

    @Test fun slidingUpLocksOnRelease() {
        assertEquals(State.willLock, step(State.recording, Event.drag(0f, -70f)))
        assertEquals(State.recording, step(State.willLock, Event.drag(0f, -69f)))
        assertEquals(State.locked, step(State.willLock, Event.release(300)))
    }

    @Test fun cancelWinsWhenBothDistancesArePassed() {
        assertEquals(State.willCancel, step(State.recording, Event.drag(-120f, -90f)))
        assertEquals(State.willCancel, step(State.willLock, Event.drag(-120f, -90f)))
    }

    @Test fun stoppingALockedRecordingShowsThePreview() {
        assertEquals(State.preview, step(State.locked, Event.stop))
    }

    @Test fun reachingTheLimitGoesToThePreviewNeverToSend() {
        assertEquals(State.preview, step(State.recording, Event.limitReached))
        assertEquals(State.preview, step(State.willCancel, Event.limitReached))
        assertEquals(State.preview, step(State.locked, Event.limitReached))
    }

    @Test fun anInterruptionKeepsThePreview() {
        assertEquals(State.preview, step(State.recording, Event.interrupted))
        assertEquals(State.preview, step(State.locked, Event.interrupted))
        assertEquals(State.idle, step(State.pending, Event.interrupted))
    }

    @Test fun accessibleActivationStartsLocked() {
        assertEquals(State.locked, step(State.idle, Event.activateAccessible))
    }

    @Test fun deleteCancelsFromLockedAndPreviewOnly() {
        assertEquals(State.cancelled, step(State.locked, Event.delete))
        assertEquals(State.cancelled, step(State.preview, Event.delete))
        assertEquals(State.recording, step(State.recording, Event.delete))
    }

    @Test fun sendFromLockedOrPreviewSends() {
        assertEquals(State.send, step(State.locked, Event.sendTapped))
        assertEquals(State.send, step(State.preview, Event.sendTapped))
    }

    @Test fun draggingOutsideARecordingChangesNothing() {
        assertEquals(State.locked, step(State.locked, Event.drag(-200f, -200f)))
        assertEquals(State.idle, step(State.idle, Event.drag(-200f, 0f)))
    }
}
