/// When to send "typing" signals for one chat's composer. Pure: the caller keeps the state and does the sending.
public enum TypingSender {
    /// A signal is repeated this often while the person keeps typing.
    public static let repeatMs: Int64 = 8_000
    /// Typing counts as over after this long without a keystroke.
    public static let idleMs: Int64 = 5_000

    public struct State: Equatable, Sendable {
        public var isTyping: Bool
        public var lastSentMs: Int64
        public var lastKeyMs: Int64
        public static let idle = State(isTyping: false, lastSentMs: 0, lastKeyMs: 0)
        public init(isTyping: Bool, lastSentMs: Int64, lastKeyMs: Int64) {
            self.isTyping = isTyping
            self.lastSentMs = lastSentMs
            self.lastKeyMs = lastKeyMs
        }
    }

    public enum Event: Equatable, Sendable {
        case textChanged(isEmpty: Bool)
        case sent
        case tick
    }

    /// `send` is `true` for "send `typing on`", `false` for "send `typing off`", `nil` for nothing.
    public static func step(_ state: State, _ event: Event, nowMs: Int64) -> (state: State, send: Bool?) {
        var next = state
        switch event {
        case .textChanged(let isEmpty):
            if isEmpty { return stop(state) }
            next.lastKeyMs = nowMs
            if !state.isTyping || nowMs - state.lastSentMs >= repeatMs {
                next.isTyping = true
                next.lastSentMs = nowMs
                return (next, true)
            }
            return (next, nil)
        case .sent:
            return stop(state)
        case .tick:
            guard state.isTyping else { return (state, nil) }
            if nowMs - state.lastKeyMs >= idleMs { return stop(state) }
            if nowMs - state.lastSentMs >= repeatMs {
                next.lastSentMs = nowMs
                return (next, true)
            }
            return (next, nil)
        }
    }

    private static func stop(_ state: State) -> (state: State, send: Bool?) {
        state.isTyping ? (.idle, false) : (state, nil)
    }
}

/// Whether to show "typing…" for the other person.
public enum TypingShown {
    public static let showMs: Int64 = 10_000

    /// True for 10 s after the last `typing on`. The caller sets `lastOnMs` to `nil` on `typing off` and when a message
    /// arrives.
    public static func isTyping(lastOnMs: Int64?, nowMs: Int64) -> Bool {
        guard let lastOnMs else { return false }
        let age = nowMs - lastOnMs
        return age >= 0 && age < showMs
    }
}

/// What the chat header says about the other person being around.
public enum PresenceLine {
    public static let onlineMs: Int64 = 90_000

    public enum Line: Equatable, Sendable {
        case online
        /// The time (ms) of the last signal or message this device witnessed.
        case lastSeen(Int64)
        case none
    }

    /// Online for 90 s after the last `online`; then the newest time witnessed; with nothing witnessed, nothing.
    public static func text(lastOnlineMs: Int64?, lastWitnessedMs: Int64?, nowMs: Int64) -> Line {
        if let lastOnlineMs, nowMs - lastOnlineMs < onlineMs, nowMs >= lastOnlineMs { return .online }
        let newest = [lastOnlineMs, lastWitnessedMs].compactMap { $0 }.max()
        return newest.map(Line.lastSeen) ?? .none
    }

    /// `away` ends "online" at once; the moment it came counts as witnessed.
    public static func afterAway(nowMs: Int64) -> (lastOnlineMs: Int64?, lastWitnessedMs: Int64?) {
        (nil, nowMs)
    }
}
