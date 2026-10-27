public enum SimulatedNetwork: Sendable {
    case good, weak, veryWeak, lost
}

/// A call engine that plays network conditions on demand, so call screens work without real calls.
public final class SimulatedCallEngine: CallEngine {
    public static let reconnectTimeout: Double = 30

    public private(set) var state: CallState? {
        didSet { onChange?(state) }
    }
    public var onChange: ((CallState?) -> Void)?

    public var server: ServerPolicy { didSet { recompute() } }
    public var settings: CallSettings { didSet { recompute() } }
    private var network: NetworkType
    private var condition: SimulatedNetwork = .good
    private var reconnectingFor: Double = 0

    public init(server: ServerPolicy, settings: CallSettings, network: NetworkType) {
        self.server = server
        self.settings = settings
        self.network = network
    }

    public func start(with contact: Contact, video: Bool) {
        condition = .good
        reconnectingFor = 0
        let videoOn = video && !(network == .mobile && settings.videoOffOnMobile)
        state = CallState(contact: contact, phase: .connecting, isVideoCall: video, isVideoOn: videoOn,
                          isMuted: false, isSpeakerOn: video, quality: .good, banner: nil, elapsed: 0,
                          bytesUsed: 0, inCallPreference: nil, effectiveMode: .ultraLow, network: network)
        recompute()
    }

    public func setPreference(_ preference: ModePreference) {
        update { $0.inCallPreference = preference }
    }

    public func setMuted(_ on: Bool) { update { $0.isMuted = on } }
    public func setSpeaker(_ on: Bool) { update { $0.isSpeakerOn = on } }

    public func setVideo(_ on: Bool) {
        update { $0.isVideoOn = on && self.condition != .veryWeak }
    }

    public func end() {
        update { $0.phase = .ended(.hungUp); $0.banner = nil }
    }

    public func setNetwork(_ type: NetworkType) {
        network = type
        update { $0.network = type }
    }

    public func simulate(_ newCondition: SimulatedNetwork) {
        guard let current = state, !current.isEnded else { return }
        condition = newCondition
        update { s in
            switch newCondition {
            case .good:
                s.phase = .active
                s.quality = .good
                s.banner = nil
                self.reconnectingFor = 0
            case .weak:
                s.phase = .active
                s.quality = .weak
                s.banner = s.isVideoOn ? .videoLowered : nil
            case .veryWeak:
                s.phase = .active
                s.quality = .weak
                s.isVideoOn = false
                s.banner = .audioOnly
            case .lost:
                s.phase = .reconnecting
                s.quality = .weak
                s.banner = .reconnecting
            }
        }
    }

    /// Moves the simulated clock forward.
    public func advance(by seconds: Double) {
        guard let current = state, !current.isEnded else { return }
        update { s in
            switch s.phase {
            case .connecting, .active:
                s.phase = .active
                s.elapsed += seconds
                s.bytesUsed += Int64(Double(s.effectiveMode.bytesPerSecond) * seconds)
            case .reconnecting:
                self.reconnectingFor += seconds
                if self.reconnectingFor >= Self.reconnectTimeout {
                    s.phase = .ended(.connectionLost)
                    s.banner = .connectionLost
                }
            case .ended:
                break
            }
        }
    }

    // MARK: - Private

    private var sustainable: DataMode {
        switch condition {
        case .good: .high
        case .weak: .dataSaver
        case .veryWeak, .lost: .ultraLow
        }
    }

    private func update(_ change: (inout CallState) -> Void) {
        guard var s = state else { return }
        change(&s)
        s.effectiveMode = DataModePolicy.effective(server: server, settings: settings, network: network,
                                                   inCall: s.inCallPreference, sustainable: sustainable)
        state = s
    }

    private func recompute() { update { _ in } }
}

extension CallState {
    public var isEnded: Bool {
        if case .ended = phase { return true }
        return false
    }
}
