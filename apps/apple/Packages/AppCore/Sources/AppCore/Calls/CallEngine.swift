public enum CallEndReason: Equatable, Sendable {
    case hungUp, connectionLost, declined, missed, answeredElsewhere, failed
}

public enum CallPhase: Equatable, Sendable {
    case connecting, active, reconnecting
    /// Outgoing call, waiting for the other person to answer.
    case ringing
    /// A ringing call shown in-app (Mac).
    case incoming
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

    public init(contact: Contact, phase: CallPhase, isVideoCall: Bool, isVideoOn: Bool, isMuted: Bool = false,
                isSpeakerOn: Bool = false, quality: CallQuality = .good, banner: CallBanner? = nil,
                elapsed: Double = 0, bytesUsed: Int64 = 0, inCallPreference: ModePreference? = nil,
                effectiveMode: DataMode, network: NetworkType) {
        self.contact = contact; self.phase = phase; self.isVideoCall = isVideoCall; self.isVideoOn = isVideoOn
        self.isMuted = isMuted; self.isSpeakerOn = isSpeakerOn; self.quality = quality; self.banner = banner
        self.elapsed = elapsed; self.bytesUsed = bytesUsed; self.inCallPreference = inCallPreference
        self.effectiveMode = effectiveMode; self.network = network
    }

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
    func answer(video: Bool)
    func decline()
    func setNetwork(_ type: NetworkType)
    func reset()
    var onChange: ((CallState?) -> Void)? { get set }
}
