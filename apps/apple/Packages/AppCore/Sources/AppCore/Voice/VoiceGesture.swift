/// The voice-note gesture as a pure state function (spec 6.1). The Android core has the same table in Kotlin.
public enum VoiceGesture {
    public enum State: Equatable, Sendable {
        case idle, pending, recording, willCancel, willLock, locked, preview
        /// Outcomes: the caller acts on them and goes back to `idle`.
        case cancelled, send, tooShort
    }

    public enum Event: Equatable, Sendable {
        case press
        case heldFor(ms: Int)
        /// Offset of the finger from where it went down, in points. Up is negative `dy`.
        case drag(dx: Double, dy: Double)
        case release(elapsedMs: Int)
        case stop
        case limitReached
        case interrupted
        case delete
        case sendTapped
        case activateAccessible
    }

    public static let startDelayMs = 150
    public static let minimumMs = 1_000
    public static let cancelDistance = 100.0
    public static let lockDistance = 70.0

    public static func step(_ state: State, _ event: Event, rtl: Bool) -> State {
        switch (state, event) {
        case (.idle, .press): return .pending
        case (.idle, .activateAccessible): return .locked
        case (.pending, .heldFor(let ms)): return ms >= startDelayMs ? .recording : .pending
        case (.pending, .release), (.pending, .stop): return .tooShort
        case (.pending, .interrupted): return .cancelled
        case (.recording, .drag(let dx, let dy)), (.willCancel, .drag(let dx, let dy)), (.willLock, .drag(let dx, let dy)):
            let toCancel = rtl ? dx : -dx
            if toCancel >= cancelDistance { return .willCancel }
            return -dy >= lockDistance ? .willLock : .recording
        case (.recording, .release(let ms)): return ms >= minimumMs ? .send : .tooShort
        case (.willCancel, .release): return .cancelled
        case (.willLock, .release): return .locked
        case (.locked, .stop): return .preview
        case (.recording, .limitReached), (.willCancel, .limitReached), (.willLock, .limitReached), (.locked, .limitReached):
            return .preview
        case (.recording, .interrupted), (.willCancel, .interrupted), (.willLock, .interrupted), (.locked, .interrupted):
            return .preview
        case (.locked, .delete), (.preview, .delete): return .cancelled
        case (.locked, .sendTapped), (.preview, .sendTapped): return .send
        default: return state
        }
    }
}
