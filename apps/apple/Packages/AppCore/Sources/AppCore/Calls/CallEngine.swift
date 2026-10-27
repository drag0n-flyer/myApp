public enum CallEndReason: Equatable, Sendable {
    case hungUp, connectionLost
}

public enum CallPhase: Equatable, Sendable {
    case connecting, active, reconnecting
    case ended(CallEndReason)
}

public enum CallQuality: Sendable {
    case good, weak

    public var title: String {
        switch self {
        case .good: "Good"
        case .weak: "Weak"
        }
    }
}

/// Explains what the app did when the network changed.
public enum CallBanner: Equatable, Sendable {
    case videoLowered, audioOnly, reconnecting, connectionLost

    public var text: String {
        switch self {
        case .videoLowered: "Weak network: video lowered to save your voice quality"
        case .audioOnly: "Switched to audio only"
        case .reconnecting: "Reconnecting…"
        case .connectionLost: "Call ended: connection lost"
        }
    }
}

public struct CallState: Equatable, Sendable {
    public var contact: Contact
    public var phase: CallPhase
    public var isVideoCall: Bool
    public var isVideoOn: Bool
    public var isMuted: Bool
    public var isSpeakerOn: Bool
    public var quality: CallQuality
    public var banner: CallBanner?
    public var elapsed: Double
    public var bytesUsed: Int64
    public var inCallPreference: ModePreference?
    public var effectiveMode: DataMode
    public var network: NetworkType

    /// "04:12", or "1:02:05" past an hour.
    public var elapsedText: String {
        let total = Int(elapsed)
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        func two(_ n: Int) -> String { n < 10 ? "0\(n)" : "\(n)" }
        return h > 0 ? "\(h):\(two(m)):\(two(s))" : "\(two(m)):\(two(s))"
    }

    public var dataUsedText: String { DataFormat.used(bytes: bytesUsed) }
}

/// Runs calls. `SimulatedCallEngine` now; the WebRTC engine after Phase 0.
public protocol CallEngine: AnyObject {
    var state: CallState? { get }
    func start(with contact: Contact, video: Bool)
    func setPreference(_ preference: ModePreference)
    func setMuted(_ on: Bool)
    func setSpeaker(_ on: Bool)
    func setVideo(_ on: Bool)
    func end()
}
