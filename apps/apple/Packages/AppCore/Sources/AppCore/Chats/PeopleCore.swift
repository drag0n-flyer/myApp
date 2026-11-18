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
}

/// A profile update counts only when its version is higher than the one kept.
public enum ProfileMerge {
    /// Strictly higher only; versions start at 1, so 0 is never accepted.
    public static func accept(current: Int?, incoming: Int) -> Bool {
        guard incoming >= 1 else { return false }
        return incoming > (current ?? 0)
    }
}
