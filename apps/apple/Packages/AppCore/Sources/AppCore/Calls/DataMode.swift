/// Call data modes, from smallest to largest data use.
public enum DataMode: Int, CaseIterable, Comparable, Codable, Sendable {
    case ultraLow, dataSaver, balanced, high

    public static func < (lhs: DataMode, rhs: DataMode) -> Bool { lhs.rawValue < rhs.rawValue }

    public var title: String {
        switch self {
        case .ultraLow: "Ultra-low"
        case .dataSaver: "Data saver"
        case .balanced: "Balanced"
        case .high: "High quality"
        }
    }

    public var subtitle: String? {
        switch self {
        case .ultraLow: "Voice only, smallest data"
        case .dataSaver: "Clear voice, low data"
        case .balanced, .high: nil
        }
    }

    /// Estimated audio data per 10 minutes, both directions.
    /// Measured 2026-10-28 (CI, simulated), see docs/measurements/2026-10-28-data-use-ci.md
    public var estimatedMBPer10Minutes: Int {
        switch self {
        case .ultraLow: 2
        case .dataSaver: 3
        case .balanced: 5
        case .high: 6
        }
    }

    public var bytesPerSecond: Int { estimatedMBPer10Minutes * 1_000_000 / 600 }
}

/// What the user asked for: automatic, or a fixed mode.
public enum ModePreference: Equatable, Hashable, Codable, Sendable {
    case auto
    case fixed(DataMode)

    public var title: String {
        switch self {
        case .auto: "Auto"
        case .fixed(let mode): mode.title
        }
    }
}

public enum NetworkType: Sendable {
    case wifi, mobile, offline
}
