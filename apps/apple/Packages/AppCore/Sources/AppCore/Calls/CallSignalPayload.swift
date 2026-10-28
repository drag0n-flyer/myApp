import Foundation

public struct IceCandidate: Codable, Equatable, Sendable {
    public var sdp: String
    public var sdpMid: String?
    public var sdpMLineIndex: Int32
    public init(sdp: String, sdpMid: String? = nil, sdpMLineIndex: Int32) {
        self.sdp = sdp; self.sdpMid = sdpMid; self.sdpMLineIndex = sdpMLineIndex
    }
}

public struct CallSignalPayload: Codable, Equatable, Sendable {
    public enum Kind: String, Codable, Sendable { case ring, offer, answer, candidate, renegotiate, renegotiateAnswer, modeChange }
    public var kind: Kind
    public var callID: String
    public var video: Bool?             // ring
    public var sdp: String?             // offer, answer, renegotiate, renegotiateAnswer
    public var dtlsFingerprint: String? // offer, answer: "sha-256 AB:CD:…", copied from the SDP's a=fingerprint
    public var candidate: IceCandidate? // candidate
    public var ceiling: DataMode?       // ring, offer, answer, modeChange: the most this device wants to receive
    public var videoOn: Bool?           // modeChange

    public init(kind: Kind, callID: String, video: Bool? = nil, sdp: String? = nil,
                dtlsFingerprint: String? = nil, candidate: IceCandidate? = nil,
                ceiling: DataMode? = nil, videoOn: Bool? = nil) {
        self.kind = kind; self.callID = callID; self.video = video; self.sdp = sdp
        self.dtlsFingerprint = dtlsFingerprint; self.candidate = candidate
        self.ceiling = ceiling; self.videoOn = videoOn
    }

    // `ceiling` goes on the wire as a string (docs/protocol/calls.md), not as DataMode's own Int coding,
    // which stays the local-storage format (UserSettings).
    private enum CodingKeys: String, CodingKey {
        case kind, callID, video, sdp, dtlsFingerprint, candidate, ceiling, videoOn
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        kind = try c.decode(Kind.self, forKey: .kind)
        callID = try c.decode(String.self, forKey: .callID)
        video = try c.decodeIfPresent(Bool.self, forKey: .video)
        sdp = try c.decodeIfPresent(String.self, forKey: .sdp)
        dtlsFingerprint = try c.decodeIfPresent(String.self, forKey: .dtlsFingerprint)
        candidate = try c.decodeIfPresent(IceCandidate.self, forKey: .candidate)
        videoOn = try c.decodeIfPresent(Bool.self, forKey: .videoOn)
        if let name = try c.decodeIfPresent(String.self, forKey: .ceiling) {
            guard let mode = DataMode.allCases.first(where: { $0.wireName == name }) else {
                throw DecodingError.dataCorruptedError(forKey: .ceiling, in: c, debugDescription: "unknown ceiling \(name)")
            }
            ceiling = mode
        } else {
            ceiling = nil
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(kind, forKey: .kind)
        try c.encode(callID, forKey: .callID)
        try c.encodeIfPresent(video, forKey: .video)
        try c.encodeIfPresent(sdp, forKey: .sdp)
        try c.encodeIfPresent(dtlsFingerprint, forKey: .dtlsFingerprint)
        try c.encodeIfPresent(candidate, forKey: .candidate)
        try c.encodeIfPresent(ceiling?.wireName, forKey: .ceiling)
        try c.encodeIfPresent(videoOn, forKey: .videoOn)
    }
}

extension DataMode {
    /// The `ceiling` string in a call signal payload.
    var wireName: String {
        switch self {
        case .ultraLow: "ultraLow"
        case .dataSaver: "dataSaver"
        case .balanced: "balanced"
        case .high: "high"
        }
    }
}

public enum CallSealError: Error, Equatable { case wrongSender, undecryptable }
