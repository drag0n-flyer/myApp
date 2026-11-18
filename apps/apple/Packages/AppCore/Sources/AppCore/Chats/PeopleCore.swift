/// When a disappearing message goes: at its send time plus its `ttl`, on every device.
public enum Expiry {
    /// Milliseconds, or `nil` when there is no (positive) `ttl`.
    public static func expiresAt(atMs: Int64, ttlSeconds: Int?) -> Int64? {
        guard let ttlSeconds, ttlSeconds > 0 else { return nil }
        return atMs + Int64(ttlSeconds) * 1000
    }

    /// Expired when `now >= expiresAt`; a message without an expiry never is.
    public static func isExpired(_ expiresAtMs: Int64?, nowMs: Int64) -> Bool {
        guard let expiresAtMs else { return false }
        return nowMs >= expiresAtMs
    }
}

/// The choices for "Disappearing messages".
public enum TimerChoice: CaseIterable, Sendable {
    case off, day, week, quarter

    public var seconds: Int {
        switch self {
        case .off: 0
        case .day: 86_400
        case .week: 604_800
        case .quarter: 7_776_000
        }
    }

    /// `nil` for any other number.
    public static func from(seconds: Int) -> TimerChoice? {
        allCases.first { $0.seconds == seconds }
    }

    /// 0 (off), 1, 7 or 90: how the duration is worded ("1 day", "7 days", "90 days").
    public var days: Int { seconds / 86_400 }
}

/// What a timer system line says, for the view to word from the String Catalog. `name == nil` means "You".
public struct TimerLine: Equatable, Sendable {
    public var choice: TimerChoice
    public var name: String?

    public init(choice: TimerChoice, name: String?) {
        self.choice = choice
        self.name = name
    }
}

extension SystemEvent {
    /// The timer line for this event; `nil` for another event or a number that is not one of the choices.
    public var timerLine: TimerLine? {
        guard case .timer(let seconds, let name) = self, let choice = TimerChoice.from(seconds: seconds) else { return nil }
        return TimerLine(choice: choice, name: name)
    }
}

/// A profile update counts only when its version is higher than the one kept.
public enum ProfileMerge {
    /// Strictly higher only; versions start at 1, so 0 is never accepted.
    public static func accept(current: Int?, incoming: Int) -> Bool {
        guard incoming >= 1 else { return false }
        return incoming > (current ?? 0)
    }
}
